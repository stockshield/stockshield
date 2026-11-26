package com.stockshield.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.stockshield.support.RealServerTestSupport;

/**
 * 실제 /ws 연결로 STOMP 인증·구독 권한·SEND 거절 확인 (FDS_ALT_001 구현방안, MKT_VIEW_001 구현방안, USR_AUTH_003 #1·#2,
 * 연결 합의서 8절, 대기 목록 #7·#55·#83).
 * 인터셉터가 WebSocketConfig에 실제로 등록되어 있는지도 이 테스트로 확인함. 거절되면 서버가 ERROR 프레임을 보내고 연결을 끊음.
 */
class StompAuthConnectionTest extends RealServerTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(5);
    /** 거절된 구독에 메시지가 오지 않는 것을 확인할 때 기다리는 시간 */
    private static final long QUIET_MS = 500;

    @Autowired SimpMessagingTemplate broker;

    private final WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
    private final List<StompSession> sessions = new ArrayList<>();

    @AfterEach
    void disconnect() {
        sessions.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
    }

    @Test
    @DisplayName("토큰 없이 접속해 시세를 구독하면 메시지를 받는다")
    void anonymousReceivesPrice() throws Exception {
        Probe probe = new Probe();
        BlockingQueue<String> received = subscribe(connect(null, probe), "/topic/price/005930");

        awaitDelivery("/topic/price/005930", received);
        assertThat(probe.errors).isEmpty();
    }

    @Test
    @DisplayName("토큰 없이 관리자 채널을 구독하면 거절되고 관리자 경보를 받지 못한다")
    void anonymousCannotSubscribeAdmin() throws Exception {
        Probe probe = new Probe();
        BlockingQueue<String> received = subscribe(connect(null, probe), "/topic/admin/detections");

        assertThat(probe.errors.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isNotNull();
        assertNothingDelivered("/topic/admin/detections", received);
    }

    @Test
    @DisplayName("와일드카드(/topic/**)로 구독하면 거절되고 관리자 경보를 받지 못한다")
    void wildcardSubscriptionIsRejected() throws Exception {
        Probe probe = new Probe();
        BlockingQueue<String> received = subscribe(connect(null, probe), "/topic/**");

        assertThat(probe.errors.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isNotNull();
        assertNothingDelivered("/topic/admin/reconcile", received);
    }

    @Test
    @DisplayName("ROLE_USER 토큰으로는 관리자 채널을 구독할 수 없다")
    void userCannotSubscribeAdmin() throws Exception {
        TestMember m = signUp();
        Probe probe = new Probe();
        BlockingQueue<String> received = subscribe(connect(bearer(login(m)), probe), "/topic/admin/reconcile");

        assertThat(probe.errors.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isNotNull();
        assertNothingDelivered("/topic/admin/reconcile", received);
    }

    @Test
    @DisplayName("ROLE_ADMIN 토큰으로 접속하면 관리자 채널 메시지를 받는다")
    void adminReceivesAdminTopic() throws Exception {
        Probe probe = new Probe();
        BlockingQueue<String> received = subscribe(connect(bearer(loginAsAdmin(createAdmin())), probe),
                "/topic/admin/detections");

        awaitDelivery("/topic/admin/detections", received);
        assertThat(probe.errors).isEmpty();
    }

    @Test
    @DisplayName("Authorization이 틀리면 CONNECT가 거절된다")
    void badTokenConnectIsRejected() throws Exception {
        Probe probe = new Probe();
        client.connectAsync(url(), originHeaders(), stompHeaders("Bearer not-a-jwt"), probe);

        assertThat(probe.errors.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isNotNull();
    }

    @Test
    @DisplayName("클라이언트가 보낸 SEND는 거절되고 다른 구독자에게 가지 않는다(가짜 위험 의심 알림 차단)")
    void clientSendIsRejected() throws Exception {
        BlockingQueue<String> victim = subscribe(connect(null, new Probe()), "/topic/risk");
        awaitDelivery("/topic/risk", victim);

        Probe attackerProbe = new Probe();
        StompSession attacker = connect(null, attackerProbe);
        attacker.send("/topic/risk", "fake".getBytes(StandardCharsets.UTF_8));

        assertThat(attackerProbe.errors.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isNotNull();
        Thread.sleep(QUIET_MS);
        assertThat(victim).doesNotContain("fake");
    }

    // ------------------------------------------------------------------ 도우미

    private String url() {
        return "ws://127.0.0.1:" + port + "/ws";
    }

    private static WebSocketHttpHeaders originHeaders() {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin(TEST_ORIGIN);
        return headers;
    }

    private static StompHeaders stompHeaders(String authorization) {
        StompHeaders headers = new StompHeaders();
        if (authorization != null) {
            headers.add("Authorization", authorization);
        }
        return headers;
    }

    private StompSession connect(String authorization, Probe probe) throws Exception {
        StompSession session = client.connectAsync(url(), originHeaders(), stompHeaders(authorization), probe)
                .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        sessions.add(session);
        return session;
    }

    private static BlockingQueue<String> subscribe(StompSession session, String destination) {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add(new String((byte[]) payload, StandardCharsets.UTF_8));
            }
        });
        return received;
    }

    /** 구독 등록은 비동기라 받을 때까지 서버가 다시 보냄 */
    private void awaitDelivery(String destination, BlockingQueue<String> received) {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(100)).until(() -> {
            broker.convertAndSend(destination, "server");
            return !received.isEmpty();
        });
    }

    private void assertNothingDelivered(String destination, BlockingQueue<String> received) throws Exception {
        broker.convertAndSend(destination, "server");
        assertThat(received.poll(QUIET_MS, TimeUnit.MILLISECONDS)).isNull();
    }

    /** 서버가 보낸 ERROR 프레임을 모음 */
    private static final class Probe extends StompSessionHandlerAdapter {
        final BlockingQueue<StompHeaders> errors = new LinkedBlockingQueue<>();

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return byte[].class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            errors.add(headers);
        }
    }
}
