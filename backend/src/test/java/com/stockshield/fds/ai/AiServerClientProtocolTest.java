package com.stockshield.fds.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

/**
 * AI 서버(uvicorn)는 평문 HTTP/2 업그레이드 요청(Upgrade: h2c)을 400으로 거절한다(11/17 배포에서 확인).
 * 가짜 서버(JDK HttpServer)는 이 헤더를 무시해서 다른 테스트로는 잡히지 않으므로, 요청 헤더를 직접 확인한다.
 */
class AiServerClientProtocolTest {

    @Test
    @DisplayName("AI 서버 요청은 HTTP/1.1이고 HTTP/2 업그레이드 헤더(Upgrade·HTTP2-Settings)를 보내지 않는다")
    void noH2cUpgrade() throws Exception {
        AtomicReference<Headers> seen = new AtomicReference<>();
        AtomicReference<String> protocol = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            seen.set(ex.getRequestHeaders());
            protocol.set(ex.getProtocol());
            ex.getRequestBody().readAllBytes();
            String json = ex.getRequestURI().getPath().endsWith("summary")
                    ? "{\"aiSmryCont\":\"요약\",\"aiSntmScr\":0.1}"
                    : "{\"aiRsnCont\":\"설명\",\"aiChkCont\":\"확인\"}";
            byte[] res = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, res.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(res);
            }
        });
        server.start();
        try {
            AiServerClient client = new AiServerClient("http://127.0.0.1:" + server.getAddress().getPort(),
                    Duration.ofSeconds(5), Duration.ofSeconds(2));

            client.summarize("N1", "제목", "본문");
            assertThat(protocol.get()).isEqualTo("HTTP/1.1");
            assertThat(seen.get().containsKey("Upgrade")).isFalse();
            assertThat(seen.get().containsKey("Http2-settings")).isFalse();

            client.explain(1L, "WITHDRAW_LARGE", "PENDING", Map.of());
            assertThat(seen.get().containsKey("Upgrade")).isFalse();
        } finally {
            server.stop(0);
        }
    }
}
