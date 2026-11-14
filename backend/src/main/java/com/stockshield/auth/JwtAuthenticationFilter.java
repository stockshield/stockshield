package com.stockshield.auth;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authorization: Bearer {accessToken}을 검증해 로그인한 사람과 권한(ROLE_USER·ROLE_ADMIN)을 넣는다 (USR_AUTH_003 구현방안).
 * 토큰이 없거나 틀리면 아무것도 넣지 않고 넘긴다. 막을지는 SecurityConfig의 URL 권한이 정한다(401·403).
 *
 * @Component나 @Bean으로 등록하지 않고 SecurityFilterChain을 만드는 메서드 안에서 만든다.
 * 빈으로 두면 Spring Boot가 서블릿 필터로 한 번 더 등록해 두 번 돌고, @Component면 @WebMvcTest가 의존 빈을 찾지 못한다 (합의서 12절).
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtProvider jwtProvider;

    public JwtAuthenticationFilter(JwtProvider jwtProvider) {
        this.jwtProvider = jwtProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)) {
            jwtProvider.parseAccess(header.substring(BEARER.length()).trim()).ifPresent(p -> {
                var authentication = new UsernamePasswordAuthenticationToken(p, null,
                        List.of(new SimpleGrantedAuthority(p.roleCd())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        chain.doFilter(request, response);
    }
}
