package com.stockshield.auth;

import java.security.Principal;
import java.util.List;
import java.util.Set;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * STOMP 접속 인증과 구독 권한 (FDS_ALT_001 구현방안, MKT_VIEW_001 구현방안, USR_AUTH_003 #1·#2, 연결 합의서 8절, 대기 목록 #7·#55·#83).
 * SEND 거절은 WebSocketConfig의 SendFrameBlocker가 맡음.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER = "Bearer ";
    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ADMIN_PREFIX = "/topic/admin/";
    private static final String PRICE_PREFIX = "/topic/price/";
    private static final Set<String> PUBLIC_TOPICS = Set.of("/topic/ranking", "/topic/risk");

    private final JwtProvider jwtProvider;

    public StompAuthChannelInterceptor(JwtProvider jwtProvider) {
        this.jwtProvider = jwtProvider;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        // 같은 accessor에 setUser해야 Spring이 이 연결의 사용자로 기억해 뒤 프레임에 붙여 줌
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        // CONNECT와 STOMP 프레임이 모두 CONNECT 유형
        if (SimpMessageType.CONNECT.equals(accessor.getMessageType())) {
            authenticate(accessor, message);
        } else if (SimpMessageType.SUBSCRIBE.equals(accessor.getMessageType())) {
            authorizeSubscribe(accessor, message);
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor, Message<?> message) {
        String header = accessor.getFirstNativeHeader(AUTHORIZATION);
        if (header == null) {
            return;
        }
        // 토큰을 보냈는데 틀리면 익명으로 넘기지 않고 끊음. 만료를 모르고 관리자 채널이 조용히 비는 일을 막기 위해
        if (!header.startsWith(BEARER)) {
            throw new MessageDeliveryException(message, "인증할 수 없습니다");
        }
        AuthPrincipal principal = jwtProvider.parseAccess(header.substring(BEARER.length()).trim())
                .orElseThrow(() -> new MessageDeliveryException(message, "인증할 수 없습니다"));
        accessor.setUser(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority(principal.roleCd()))));
    }

    private void authorizeSubscribe(StompHeaderAccessor accessor, Message<?> message) {
        String destination = accessor.getDestination();
        if (destination != null && destination.startsWith(ADMIN_PREFIX)) {
            if (!isAdmin(accessor.getUser())) {
                throw new MessageDeliveryException(message, "구독 권한이 없습니다");
            }
            return;
        }
        // 허용 목록 방식. 기본 브로커는 /topic/** 같은 와일드카드 구독을 받아 주므로 관리자 접두사만 막으면 관리자 경보가 샘
        if (destination != null && (destination.startsWith(PRICE_PREFIX) || PUBLIC_TOPICS.contains(destination))) {
            return;
        }
        throw new MessageDeliveryException(message, "구독할 수 없는 채널입니다");
    }

    private static boolean isAdmin(Principal user) {
        return user instanceof Authentication authentication
                && authentication.getAuthorities().stream().anyMatch(a -> ROLE_ADMIN.equals(a.getAuthority()));
    }
}
