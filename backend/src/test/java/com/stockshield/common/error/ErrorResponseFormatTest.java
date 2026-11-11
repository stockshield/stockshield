package com.stockshield.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.jayway.jsonpath.JsonPath;
import com.stockshield.common.trace.TraceIdFilter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 공통 에러 응답 형식과 거래추적번호를 확인한다.
 * API 명세서 공통 규칙 1(X-Trace-Id, Retry-After)·2(에러 응답 형식)·3(에러 코드), SYS_SEC_001 #6
 */
@WebMvcTest
@Import(ErrorResponseFormatTest.ProbeController.class)
class ErrorResponseFormatTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("모든 응답에 서버가 만든 거래추적번호가 붙고, 에러 본문의 traceId와 같다")
    void traceIdInHeaderMatchesBody() throws Exception {
        MvcResult result = mockMvc.perform(get("/probe/lock-timeout").header(TraceIdFilter.HEADER, "client-made-id"))
                .andExpect(status().isServiceUnavailable())
                .andReturn();

        String headerTraceId = result.getResponse().getHeader(TraceIdFilter.HEADER);
        String bodyTraceId = JsonPath.read(result.getResponse().getContentAsString(), "$.traceId");

        assertThat(headerTraceId).isNotEqualTo("client-made-id");   // 화면이 보낸 값은 믿지 않는다
        assertThat(UUID.fromString(headerTraceId)).isNotNull();
        assertThat(bodyTraceId).isEqualTo(headerTraceId);
    }

    @Test
    @DisplayName("요청마다 다른 거래추적번호를 발급한다")
    void eachRequestGetsNewTraceId() throws Exception {
        String first = mockMvc.perform(get("/probe/ok")).andReturn().getResponse().getHeader(TraceIdFilter.HEADER);
        String second = mockMvc.perform(get("/probe/ok")).andReturn().getResponse().getHeader(TraceIdFilter.HEADER);

        assertThat(first).isNotBlank();
        assertThat(second).isNotBlank().isNotEqualTo(first);
    }

    @Test
    @DisplayName("락 대기 초과는 503 LOCK_TIMEOUT과 Retry-After: 1로 응답한다")
    void lockTimeoutResponse() throws Exception {
        mockMvc.perform(get("/probe/lock-timeout"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("LOCK_TIMEOUT"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.detail").isMap());
    }

    @Test
    @DisplayName("DB가 락 대기 초과를 알리면(55P03) 같은 503 LOCK_TIMEOUT으로 응답한다")
    void databaseLockTimeoutIsTranslated() throws Exception {
        mockMvc.perform(get("/probe/db-lock-timeout"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("LOCK_TIMEOUT"));
    }

    @Test
    @DisplayName("입력값 검증 실패와 깨진 본문은 400 INVALID_REQUEST로 응답한다")
    void invalidInputResponse() throws Exception {
        mockMvc.perform(post("/probe/validate").contentType(MediaType.APPLICATION_JSON).content("{\"ordQty\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        mockMvc.perform(post("/probe/validate").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("없는 경로는 404 RESOURCE_NOT_FOUND로 응답한다")
    void unknownPathResponse() throws Exception {
        mockMvc.perform(get("/probe/no-such-path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(header().exists(TraceIdFilter.HEADER));
    }

    @Test
    @DisplayName("예상하지 못한 오류는 500 INTERNAL_ERROR로 응답하고 내부 메시지를 노출하지 않는다")
    void unexpectedErrorResponse() throws Exception {
        MvcResult result = mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("secret-internal-detail");
    }

    /** 테스트 전용 컨트롤러. @TestComponent라 다른 테스트의 컴포넌트 스캔에는 잡히지 않는다. */
    @TestComponent
    @RestController
    static class ProbeController {

        @GetMapping("/probe/ok")
        String ok() {
            return "ok";
        }

        @GetMapping("/probe/lock-timeout")
        void lockTimeout() {
            throw new ApiException(ErrorCode.LOCK_TIMEOUT);
        }

        @GetMapping("/probe/db-lock-timeout")
        void dbLockTimeout() {
            throw new CannotAcquireLockException("canceling statement due to lock timeout");
        }

        @PostMapping("/probe/validate")
        void validate(@Valid @RequestBody ProbeRequest request) {
        }

        @GetMapping("/probe/boom")
        void boom() {
            throw new IllegalStateException("secret-internal-detail");
        }
    }

    record ProbeRequest(@NotNull @Min(1) Long ordQty) {
    }
}
