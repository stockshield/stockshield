package com.stockshield.auth;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockshield.common.error.ErrorCode;
import com.stockshield.common.error.ErrorResponse;
import com.stockshield.common.trace.TraceIds;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 보안 필터에서 끝나는 401·403도 공통 에러 형식(code·message·detail·traceId)으로 응답한다 (API 명세서 '공통 규칙' 2·3).
 * 토큰 없음·만료는 401 UNAUTHORIZED, 권한 부족은 403 ACCESS_DENIED. 403은 권한 거부에만 쓴다(USR_AUTH_003 #2).
 * 거래추적번호는 이 필터보다 먼저 도는 TraceIdFilter가 MDC에 넣어 둔다.
 */
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public SecurityErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        write(response, ErrorCode.UNAUTHORIZED);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        write(response, ErrorCode.ACCESS_DENIED);
    }

    private void write(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(code, Map.of(), TraceIds.current()));
    }
}
