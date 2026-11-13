package com.stockshield.ws;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP WebSocket (연결 합의서 v0.0.8 8절, 합의 17).
 *
 * - 엔드포인트 /ws, 브로커 /topic. SockJS는 쓰지 않고 순수 WebSocket으로 연다(화면은 STOMP.js).
 * - 채널: /topic/price/{종목코드}, /topic/ranking, /topic/risk(누구나), /topic/admin/detections, /topic/admin/market,
 *   /topic/admin/reconcile(ROLE_ADMIN). 관리자 채널의 구독 인증은 조성우가 만드는 STOMP 인증 인터셉터가 맡고,
 *   그 인터셉터가 들어오면 여기에 함께 등록한다.
 * - 클라이언트가 보내는 SEND 프레임은 모두 거절한다. 기본 브로커는 클라이언트가 /topic/**로 보낸 메시지를
 *   그대로 구독자에게 뿌리므로, 막지 않으면 누구나 가짜 시세나 가짜 관제 경보를 띄울 수 있다. 발행은 서버 코드만 한다.
 * - 허용 Origin은 REST CORS와 같은 stockshield.cors.allowed-origins를 읽는다(기본 http://localhost:5173).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final String[] allowedOrigins;

    public WebSocketConfig(@Value("${stockshield.cors.allowed-origins:http://localhost:5173}") String allowedOrigins) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new SendFrameBlocker());
    }

    /** 클라이언트의 SEND 프레임을 거절한다 */
    static class SendFrameBlocker implements ChannelInterceptor {
        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
            if (accessor != null && StompCommand.SEND.equals(accessor.getCommand())) {
                throw new MessageDeliveryException(message, "클라이언트는 메시지를 보낼 수 없습니다");
            }
            return message;
        }
    }
}
