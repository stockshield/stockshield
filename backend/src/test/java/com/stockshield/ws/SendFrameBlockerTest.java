package com.stockshield.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

/** 클라이언트 SEND 프레임 차단 (연결 합의서 8절 합의 17: 발행은 서버 코드만 한다) */
class SendFrameBlockerTest {

    final WebSocketConfig.SendFrameBlocker blocker = new WebSocketConfig.SendFrameBlocker();

    @Test
    @DisplayName("클라이언트가 /topic으로 보내는 SEND는 거절하고, 구독(SUBSCRIBE)은 통과시킨다")
    void blocksSendOnly() {
        Message<byte[]> send = frame(StompCommand.SEND, "/topic/admin/detections");
        assertThatThrownBy(() -> blocker.preSend(send, null)).isInstanceOf(MessageDeliveryException.class);

        Message<byte[]> subscribe = frame(StompCommand.SUBSCRIBE, "/topic/price/005930");
        assertThat(blocker.preSend(subscribe, null)).isSameAs(subscribe);
    }

    private static Message<byte[]> frame(StompCommand command, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
