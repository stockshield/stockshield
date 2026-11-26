package com.stockshield.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.stockshield.support.MutableClock;

/**
 * JWT 발급·검증 (USR_AUTH_002 #1, SYS_SEC_001 #2, 문서 수정 대기 목록 #22)
 */
class JwtProviderTest {

    static final String SECRET = "unit-test-secret-0123456789-abcdefghij";

    final MutableClock clock = new MutableClock();
    final JwtProvider jwt = new JwtProvider(SECRET, clock);

    @Test
    @DisplayName("JWT_SECRET이 비었거나 32바이트 미만이면 기동하지 않는다")
    void refusesWeakSecret() {
        assertThatThrownBy(() -> new JwtProvider("", clock)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtProvider(null, clock)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtProvider("x".repeat(31), clock)).isInstanceOf(IllegalStateException.class);
        assertThat(new JwtProvider("x".repeat(32), clock)).isNotNull();
    }

    @Test
    @DisplayName("설정에 JWT_SECRET이 없으면(.env 없음) 애플리케이션 컨텍스트가 뜨지 않는다")
    void contextFailsWithoutSecret() {
        new ApplicationContextRunner()
                .withBean(Clock.class, () -> clock)
                .withUserConfiguration(JwtProvider.class)
                .run(context -> assertThat(context).hasFailed());

        new ApplicationContextRunner()
                .withBean(Clock.class, () -> clock)
                .withUserConfiguration(JwtProvider.class)
                .withPropertyValues("stockshield.auth.jwt-secret=" + SECRET)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(JwtProvider.class));
    }

    @Test
    @DisplayName("Access Token은 30분 동안만 유효하다(고정 Clock으로 경계 확인)")
    void accessTokenExpiresAfter30Minutes() {
        LocalDateTime t = LocalDateTime.of(2026, 11, 13, 10, 0, 0);
        clock.set(t);
        String token = jwt.createAccess(42L, "ROLE_USER");

        clock.set(t.plus(JwtProvider.ACCESS_TTL).minusSeconds(1));
        assertThat(jwt.parseAccess(token)).hasValueSatisfying(p -> {
            assertThat(p.mbrNo()).isEqualTo(42L);
            assertThat(p.roleCd()).isEqualTo("ROLE_USER");
        });

        clock.set(t.plus(JwtProvider.ACCESS_TTL));
        assertThat(jwt.parseAccess(token)).isEmpty();
    }

    @Test
    @DisplayName("Refresh Token은 7일 동안 유효하고, Access Token 자리에 쓸 수 없다(반대도 같다)")
    void refreshTokenTypeAndLifetime() {
        LocalDateTime t = LocalDateTime.of(2026, 11, 13, 10, 0, 0);
        clock.set(t);
        JwtProvider.IssuedRefresh refresh = jwt.createRefresh(7L);
        String access = jwt.createAccess(7L, "ROLE_USER");

        assertThat(jwt.parseAccess(refresh.token())).isEmpty();
        assertThat(jwt.parseRefresh(access)).isEmpty();

        clock.set(t.plus(JwtProvider.REFRESH_TTL).minus(Duration.ofSeconds(1)));
        assertThat(jwt.parseRefresh(refresh.token())).hasValueSatisfying(r -> {
            assertThat(r.mbrNo()).isEqualTo(7L);
            assertThat(r.jti()).isEqualTo(refresh.jti());
        });
        clock.set(t.plus(JwtProvider.REFRESH_TTL));
        assertThat(jwt.parseRefresh(refresh.token())).isEmpty();
    }

    @Test
    @DisplayName("조작된 토큰과 다른 비밀값으로 서명한 토큰은 거절한다")
    void rejectsTamperedAndForeignTokens() {
        String token = jwt.createAccess(1L, "ROLE_USER");
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA" + "." + parts[2];
        assertThat(jwt.parseAccess(tampered)).isEmpty();

        String foreign = new JwtProvider("another-secret-0123456789-abcdefghijk", clock).createAccess(1L, "ROLE_ADMIN");
        assertThat(jwt.parseAccess(foreign)).isEmpty();
        assertThat(jwt.parseAccess("not.a.jwt")).isEmpty();
        assertThat(jwt.parseAccess("")).isEmpty();
    }
}
