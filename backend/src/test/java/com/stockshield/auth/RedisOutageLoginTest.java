package com.stockshield.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.stockshield.support.IntegrationTestSupport;

/**
 * Redis 장애 중 로그인 (USR_AUTH_002 #5, FDS_LGN_001 #5, SYS_CORE_002 #5, API 명세서 'API_명세서' 5~9행).
 *
 * 공용 Redis는 멈추지 않는다(같은 실행의 다른 테스트가 깨진다). 이 클래스 전용 Redis 컨테이너를 띄우고 docker pause로 멈춘다.
 * 멈춘 동안 Redis 호출은 타임아웃 200ms(SYS_CORE_001 구현방안)에 걸려 실패한다.
 * 멈춘 동안 보낸 명령은 다시 켜면 Redis가 늦게 실행하므로, 테스트마다 새 회원을 쓰고 장애가 끝난 뒤의 Redis 상태는 확인하지 않는다.
 */
class RedisOutageLoginTest extends IntegrationTestSupport {

    static final GenericContainer<?> OWN_REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.2.17")).withExposedPorts(6379);

    @DynamicPropertySource
    static void ownRedis(DynamicPropertyRegistry registry) {
        OWN_REDIS.start();
        registry.add("spring.data.redis.host", OWN_REDIS::getHost);
        registry.add("spring.data.redis.port", () -> OWN_REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired JdbcTemplate jdbcTemplate;

    private boolean paused;

    @AfterEach
    void resumeRedis() {
        if (paused) {
            DockerClientFactory.instance().client().unpauseContainerCmd(OWN_REDIS.getContainerId()).exec();
            paused = false;
        }
    }

    @Test
    @DisplayName("아는 IP·기기는 로그인되고 Access Token만 받는다(refreshToken = null). 그 토큰으로 API를 쓸 수 있다")
    void knownAccessGetsAccessTokenOnly() throws Exception {
        TestMember m = signUp();
        pauseRedis();

        MvcResult r = mockMvc.perform(loginRequest(m.lgnId(), TEST_PASSWORD, m.deviceId(), m.ip()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isEmpty())
                .andReturn();
        Tokens t = tokens(r);
        assertThat(t.refreshToken()).isNull();

        mockMvc.perform(get("/api/v1/members/me").header("Authorization", bearer(t)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("실패 횟수는 login_history의 최근 30분 연속 실패로 센다: 5회째 423, 맞는 비밀번호도 423")
    void failuresCountedFromHistory() throws Exception {
        TestMember m = signUp();
        pauseRedis();

        for (int i = 0; i < 4; i++) {
            mockMvc.perform(loginRequest(m.lgnId(), "Wrong123!", m.deviceId(), m.ip()))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(loginRequest(m.lgnId(), "Wrong123!", m.deviceId(), m.ip()))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("LOGIN_LOCKED"));
        mockMvc.perform(loginRequest(m.lgnId(), TEST_PASSWORD, m.deviceId(), m.ip()))
                .andExpect(status().isLocked());
    }

    @Test
    @DisplayName("새 접속은 막는다: 503 AUTH_UNAVAILABLE, 탐지 기록은 FAIL로 닫는다")
    void newAccessBlocked() throws Exception {
        TestMember m = signUp();
        pauseRedis();

        mockMvc.perform(loginRequest(m.lgnId(), TEST_PASSWORD, newDeviceId(), m.ip()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_UNAVAILABLE"));
        assertThat(jdbcTemplate.queryForList(
                "SELECT rslt_cd FROM fds_detection WHERE mbr_no = ? AND rule_cd = 'LOGIN_NEW_ACCESS'", String.class,
                m.mbrNo())).containsExactly("FAIL");
    }

    @Test
    @DisplayName("인증 코드 확인·재발송·가입 코드 발송·토큰 재발급·로그아웃은 503 AUTH_UNAVAILABLE")
    void codeAndTokenOperationsUnavailable() throws Exception {
        TestMember m = signUp();
        Tokens t = login(m);
        MvcResult accepted = mockMvc.perform(loginRequest(m.lgnId(), TEST_PASSWORD, newDeviceId(), m.ip()))
                .andExpect(status().isAccepted())
                .andReturn();
        String authId = body(accepted).path("authId").asText();
        pauseRedis();

        mockMvc.perform(verifyRequest(authId, "000000"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_UNAVAILABLE"));
        mockMvc.perform(post("/api/v1/auth/email-resend").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("authId", authId))))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(emailCodeRequest(emailOf(uniqueLgnId())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_UNAVAILABLE"));
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", t.refreshToken()))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_UNAVAILABLE"));
        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer(t))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", t.refreshToken()))))
                .andExpect(status().isServiceUnavailable());
    }

    private void pauseRedis() {
        DockerClientFactory.instance().client().pauseContainerCmd(OWN_REDIS.getContainerId()).exec();
        paused = true;
    }
}
