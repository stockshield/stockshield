package com.stockshield.support;

import java.time.Clock;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import com.stockshield.auth.JwtProvider;
import com.stockshield.auth.SecurityConfig;
import com.stockshield.common.config.ClockConfig;

/**
 * @WebMvcTest가 올리지 않는 SecurityConfig와 그 의존 빈을 테스트 값으로 올린다 (연결 합의서 12절).
 * 이 설정 없이는 Spring Security 기본 보안이 켜져 슬라이스 테스트의 모든 요청이 401이 된다.
 * @WebMvcTest(controllers = …)와 함께 @Import한다.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(SecurityConfig.class)
public class WebMvcSecurityTestConfig {

    /** 테스트 전용 JWT 서명 비밀값(32바이트 이상). 운영 값과 무관하다 */
    public static final String TEST_JWT_SECRET = "test-only-jwt-secret-0123456789-abcdefghijklmnop";

    @Bean
    JwtProvider jwtProvider() {
        return new JwtProvider(TEST_JWT_SECRET, Clock.system(ClockConfig.ZONE));
    }
}
