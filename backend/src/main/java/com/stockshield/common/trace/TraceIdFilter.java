package com.stockshield.common.trace;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 모든 요청에 서버가 거래추적번호(UUID)를 발급해 로그(MDC)와 응답 헤더 X-Trace-Id에 같은 값으로 남긴다.
 * 화면이 보낸 X-Trace-Id는 믿지 않는다. (SYS_SEC_001 #6, API 명세서 공통 규칙 1. 공통 헤더)
 * 가장 먼저 실행되어 인증 실패·입력 오류 응답에도 번호가 붙는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString();
        MDC.put(TraceIds.MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TraceIds.MDC_KEY);
        }
    }
}
