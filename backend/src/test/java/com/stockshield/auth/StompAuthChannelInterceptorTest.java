package com.stockshield.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.stockshield.common.config.ClockConfig;
import com.stockshield.support.WebMvcSecurityTestConfig;

/** STOMP 접속 인증·구독 권한 단위 테스트 (FDS_ALT_001 구현방안, MKT_VIEW_001 구현방안, USR_AUTH_003 #1·#2, 대기 목록 #7·#83) */
class StompAuthChannelInterceptorTest {

    static final Instant NOW = Instant.parse("2026-11-16T01:00:00Z");

    final JwtProvider jwtProvider = providerAt(NOW);
    final StompAuthChannelInterceptor interceptor = new StompAuthChannelInterceptor(jwtProvider);

    @Test
    @DisplayName("CONNECT에 Authorization이 없으면 익명으로 통과한다")
    void connectWithoutTokenIsAnonymous() {
        Message<byte[]> connect = connect(StompCommand.CONNECT, null);

        assertThat(interceptor.preSend(connect, null)).isSameAs(connect);
        assertThat(userOf(connect)).isNull();
    }

    @Test
    @DisplayName("CONNECT·STOMP 프레임의 Access Token이 맞으면 회원번호와 권한을 연결의 사용자로 넣는다")
    void connectWithAccessTokenSetsUser() {
        for (StompCommand command : List.of(StompCommand.CONNECT, StompCommand.STOMP)) {
            Message<byte[]> connect = connect(command, "Bearer " + jwtProvider.createAccess(7L, "ROLE_USER"));

            interceptor.preSend(connect, null);

            Authentication user = (Authentication) userOf(connect);
            assertThat(user.getPrincipal()).isEqualTo(new AuthPrincipal(7L, "ROLE_USER"));
            assertThat(user.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
        }
    }

    @Test
    @DisplayName("Authorization을 보냈는데 Bearer 형식이 아니거나 토큰이 변조·위조·만료·Refresh Token이면 연결을 거절한다")
    void connectWithBadTokenIsRejected() {
        String expired = providerAt(NOW.minus(JwtProvider.ACCESS_TTL)).createAccess(7L, "ROLE_ADMIN");
        String refresh = jwtProvider.createRefresh(7L).token();
        String otherKey = new JwtProvider("another-secret-for-forgery-0123456789-abcdefgh",
                Clock.fixed(NOW, ClockConfig.ZONE)).createAccess(7L, "ROLE_ADMIN");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("빈 값", "");
        headers.put("Bearer 아님", "Basic abc");
        headers.put("토큰 없음", "Bearer ");
        headers.put("Bearer 없이 토큰만", jwtProvider.createAccess(7L, "ROLE_ADMIN"));
        headers.put("권한을 ROLE_ADMIN으로 고친 토큰", "Bearer " + withRole(jwtProvider.createAccess(7L, "ROLE_USER"), "ROLE_ADMIN"));
        headers.put("다른 비밀값으로 서명", "Bearer " + otherKey);
        headers.put("만료", "Bearer " + expired);
        headers.put("Refresh Token", "Bearer " + refresh);

        List<String> accepted = new ArrayList<>();
        headers.forEach((name, header) -> {
            try {
                interceptor.preSend(connect(StompCommand.CONNECT, header), null);
                accepted.add(name);
            } catch (MessageDeliveryException expectedRejection) {
                // 거절됨
            }
        });
        assertThat(accepted).as("거절되지 않은 값").isEmpty();
    }

    @Test
    @DisplayName("시세·순위·위험 의심 채널은 토큰 없이 구독할 수 있다")
    void publicTopicsNeedNoToken() {
        for (String destination : List.of("/topic/price/005930", "/topic/ranking", "/topic/risk")) {
            Message<byte[]> subscribe = subscribe(destination, null);
            assertThat(interceptor.preSend(subscribe, null)).as(destination).isSameAs(subscribe);
        }
    }

    @Test
    @DisplayName("관리자 채널은 ROLE_ADMIN만 구독할 수 있다: 익명·ROLE_USER는 거절")
    void adminTopicsNeedAdmin() {
        for (String destination : List.of("/topic/admin/detections", "/topic/admin/reconcile", "/topic/admin/market")) {
            assertThatThrownBy(() -> interceptor.preSend(subscribe(destination, null), null))
                    .as(destination).isInstanceOf(MessageDeliveryException.class);
            assertThatThrownBy(() -> interceptor.preSend(subscribe(destination, user("ROLE_USER")), null))
                    .as(destination).isInstanceOf(MessageDeliveryException.class);

            Message<byte[]> byAdmin = subscribe(destination, user("ROLE_ADMIN"));
            assertThat(interceptor.preSend(byAdmin, null)).as(destination).isSameAs(byAdmin);
        }
    }

    @Test
    @DisplayName("허용 목록 밖 채널은 관리자도 구독할 수 없다(와일드카드로 관리자 경보를 받는 것을 막음)")
    void destinationsOutsideAllowListAreRejected() {
        List<String> destinations = List.of("/topic/**", "/topic/*/detections", "/topic/{any}/reconcile", "/topic/admin",
                "/topic/adminx/detections", "/topic/price", "/topic/ranking/x", "/queue/detections", "/app/x");
        for (String destination : destinations) {
            for (Principal user : Arrays.asList(null, user("ROLE_USER"), user("ROLE_ADMIN"))) {
                assertThatThrownBy(() -> interceptor.preSend(subscribe(destination, user), null))
                        .as(destination).isInstanceOf(MessageDeliveryException.class);
            }
        }
        assertThatThrownBy(() -> interceptor.preSend(subscribe(null, user("ROLE_ADMIN")), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    private static JwtProvider providerAt(Instant instant) {
        return new JwtProvider(WebMvcSecurityTestConfig.TEST_JWT_SECRET, Clock.fixed(instant, ClockConfig.ZONE));
    }

    /** 서명은 그대로 두고 본문의 권한만 바꿈 */
    private static String withRole(String token, String roleCd) {
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("ROLE_USER", roleCd);
        return parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
    }

    private static Authentication user(String roleCd) {
        return new UsernamePasswordAuthenticationToken(new AuthPrincipal(1L, roleCd), null,
                List.of(new SimpleGrantedAuthority(roleCd)));
    }

    private static Message<byte[]> connect(StompCommand command, String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (authorization != null) {
            accessor.setNativeHeader("Authorization", authorization);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    /** 연결 때 넣은 사용자는 Spring이 이후 프레임의 simpUser 헤더로 붙여 줌. 여기서는 그 헤더를 직접 넣음 */
    private static Message<byte[]> subscribe(String destination, Principal user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setSubscriptionId("sub-0");
        accessor.setUser(user);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Principal userOf(Message<?> message) {
        return MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class).getUser();
    }
}
