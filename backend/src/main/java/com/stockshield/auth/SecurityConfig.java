package com.stockshield.auth;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * URL 권한과 CORS (USR_AUTH_003 #1·#2·#4, 연결 합의서 8절, 문서 수정 대기 목록 #6·#9·#55).
 *
 * <pre>
 * 누구나        회원/인증 API('API_명세서' 2~8행), 시세·종목·뉴스 조회(GET /api/v1/market/**, /api/v1/news/**), /ws 핸드셰이크
 * USER·ADMIN   로그아웃(9행), GET /api/v1/members/me
 * ROLE_USER    주문·계좌·출금(/api/v1/orders/**, /api/v1/accounts/**). 관리자는 계좌가 없고 거래할 수 없다(직무 분리)
 * ROLE_ADMIN   /api/v1/admin/**
 * 토큰 검사 밖  /api/v1/internal/** (X-Internal-Token은 그 API가 상수 시간 비교로 확인한다, 합의서 9절)
 * 그 밖의 /api/** 는 로그인해야 한다. 새 API를 이 표에 넣지 않으면 열리지 않고 닫힌다.
 * </pre>
 *
 * 세션을 쓰지 않는 JWT 방식이라 CSRF 토큰과 폼 로그인을 끈다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    static final List<String> ALLOWED_HEADERS = List.of("Authorization", "Content-Type", "X-Device-Id");
    static final List<String> EXPOSED_HEADERS = List.of("X-Trace-Id", "Idempotency-Replayed", "Retry-After");

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtProvider jwtProvider, ObjectMapper objectMapper,
                                                   CorsConfigurationSource corsConfigurationSource) throws Exception {
        SecurityErrorHandler errors = new SecurityErrorHandler(objectMapper);
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/members/check-id").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/members/email-code", "/api/v1/members").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/email-verify",
                                "/api/v1/auth/email-resend", "/api/v1/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").hasAnyRole("USER", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/members/me").hasAnyRole("USER", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/market/**", "/api/v1/news/**").permitAll()
                        .requestMatchers("/api/v1/internal/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/orders/**", "/api/v1/accounts/**").hasRole("USER")
                        .requestMatchers("/api/**").authenticated()
                        .requestMatchers("/ws", "/ws/**").permitAll()
                        .anyRequest().permitAll())
                // 필터는 빈으로 등록하지 않는다(서블릿 필터 이중 등록·@WebMvcTest 기동 실패 방지, 합의서 12절)
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 화면은 다른 출처(Vite 5173)에서 메인 서버를 직접 부른다. 허용 출처는 WebSocketConfig와 같은 키를 읽는다.
     * 허용 요청 헤더 Authorization·Content-Type·X-Device-Id, 노출 응답 헤더 X-Trace-Id·Idempotency-Replayed·Retry-After (대기 목록 #6).
     * 토큰은 헤더로 보내므로 쿠키 자격 증명은 허용하지 않는다.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${stockshield.cors.allowed-origins:http://localhost:5173}") String allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(ALLOWED_HEADERS);
        config.setExposedHeaders(EXPOSED_HEADERS);
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
