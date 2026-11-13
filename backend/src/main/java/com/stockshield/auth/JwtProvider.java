package com.stockshield.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * JWT 발급·검증 (USR_AUTH_002 #1: Access 30분, Refresh 7일).
 *
 * - HS256 서명. 비밀값(stockshield.auth.jwt-secret = JWT_SECRET)이 비었거나 32바이트(256비트) 미만이면 기동을 멈춘다.
 *   빈 값이나 짧은 값으로 서명하면 토큰을 위조할 수 있다 (대기 목록 #22, SYS_SEC_001 #2).
 * - 만료는 공용 Clock으로 판단해 고정 Clock 테스트로 30분·7일 경계를 재현한다.
 * - typ(access·refresh)를 넣어 Refresh Token을 Access Token으로 쓰지 못하게 한다.
 * - Refresh Token은 jti를 Redis(auth:refresh:{jti})에 두어 로그아웃으로 무효화한다(RefreshTokenStore).
 */
@Component
public class JwtProvider {

    public static final Duration ACCESS_TTL = Duration.ofMinutes(30);
    public static final Duration REFRESH_TTL = Duration.ofDays(7);
    static final int MIN_SECRET_BYTES = 32;

    private static final String ROLE = "role";
    private static final String TYPE = "typ";
    private static final String ACCESS = "access";
    private static final String REFRESH = "refresh";

    /** 발급한 Refresh Token과 Redis에 둘 jti */
    public record IssuedRefresh(String token, String jti, long mbrNo) {
    }

    private final SecretKey key;
    private final Clock clock;

    public JwtProvider(@Value("${stockshield.auth.jwt-secret:}") String secret, Clock clock) {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET(stockshield.auth.jwt-secret)이 비었거나 32바이트 미만입니다. infra/.env에 32바이트 이상의 임의 값을 넣으세요");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.clock = clock;
    }

    public String createAccess(long mbrNo, String roleCd) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(String.valueOf(mbrNo))
                .claim(ROLE, roleCd)
                .claim(TYPE, ACCESS)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ACCESS_TTL)))
                .signWith(key)
                .compact();
    }

    public IssuedRefresh createRefresh(long mbrNo) {
        Instant now = clock.instant();
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .subject(String.valueOf(mbrNo))
                .claim(TYPE, REFRESH)
                .id(jti)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(REFRESH_TTL)))
                .signWith(key)
                .compact();
        return new IssuedRefresh(token, jti, mbrNo);
    }

    /** 서명·만료·종류가 맞는 Access Token이면 로그인한 사람, 아니면 empty */
    public Optional<AuthPrincipal> parseAccess(String token) {
        return parse(token, ACCESS).map(c -> new AuthPrincipal(Long.parseLong(c.getSubject()), c.get(ROLE, String.class)));
    }

    /** 서명·만료·종류가 맞는 Refresh Token이면 회원번호와 jti, 아니면 empty */
    public Optional<IssuedRefresh> parseRefresh(String token) {
        return parse(token, REFRESH).map(c -> new IssuedRefresh(token, c.getId(), Long.parseLong(c.getSubject())));
    }

    private Optional<Claims> parse(String token, String type) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            // jjwt는 만료 시각과 같은 순간을 아직 유효로 본다. 만료 시각부터는 거절한다(RFC 7519 4.1.4: 현재가 exp '전'이어야 유효)
            if (claims.getExpiration() == null || !clock.instant().isBefore(claims.getExpiration().toInstant())) {
                return Optional.empty();
            }
            if (!type.equals(claims.get(TYPE, String.class)) || claims.getSubject() == null) {
                return Optional.empty();
            }
            Long.parseLong(claims.getSubject());
            return Optional.of(claims);
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
