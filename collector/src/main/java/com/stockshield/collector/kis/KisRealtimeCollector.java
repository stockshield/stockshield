package com.stockshield.collector.kis;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockshield.collector.CollectorProperties;
import com.stockshield.collector.CollectorProperties.Kis;
import com.stockshield.collector.CollectorProperties.Stock;
import com.stockshield.collector.TickMessage;
import com.stockshield.collector.TickPublisher;

/**
 * 한국투자증권 웹소켓으로 서비스 대상 종목의 실시간 체결가(H0STCNT0)를 구독한다 (MKT_TICK_001 #1·#2).
 *
 * - 접속키(approval_key)를 REST /oauth2/Approval로 받아 웹소켓 구독 요청에 쓴다. 앱 키·시크릿·접속키는 로그에 남기지 않는다.
 * - 한 세션의 실시간 등록 한도는 41건이고 (TR × 종목) 단위로 센다. 체결가 30종목 = 30건.
 * - 서버가 보내는 PINGPONG은 그대로 돌려보낸다.
 * - 연결이 끊기거나 실패하면 5초 뒤 다시 연결한다(장애 대응). 그동안 메인 서버는 마지막 시세를 유지한다.
 */
@Component
public class KisRealtimeCollector {

    private static final Logger log = LoggerFactory.getLogger(KisRealtimeCollector.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final CollectorProperties props;
    private final TickPublisher publisher;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicLong received = new AtomicLong();
    private volatile boolean running = true;

    public KisRealtimeCollector(CollectorProperties props, TickPublisher publisher, ObjectMapper objectMapper) {
        this.props = props;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
    }

    /** 끊기면 다시 연결하는 루프. 별도 스레드에서 부른다 */
    public void runForever() {
        Kis kis = props.kis();
        log.info("한국투자증권 실시간 체결가 수집 시작 {} 종목 {}개", kis, props.stocks().size());
        while (running) {
            try {
                String approvalKey = approvalKey(kis);
                CountDownLatch closed = new CountDownLatch(1);
                WebSocket ws = http.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .buildAsync(URI.create(kis.wsUrl() + "/tryitout/" + KisTickParser.TR_ID), new Listener(closed))
                        .join();
                subscribe(ws, approvalKey, props.stocks());
                closed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warn("한국투자증권 연결 실패: {}", rootMessage(e));
            }
            if (running) {
                log.info("{}초 뒤 다시 연결합니다 (받은 체결 누적 {}건)", kis.reconnectDelay().toSeconds(), received.get());
                sleep(kis.reconnectDelay());
            }
        }
    }

    public void stop() {
        running = false;
    }

    private String approvalKey(Kis kis) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "grant_type", "client_credentials", "appkey", kis.appKey(), "secretkey", kis.appSecret()));
            HttpRequest req = HttpRequest.newBuilder(URI.create(kis.restUrl() + "/oauth2/Approval"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode node = objectMapper.readTree(res.body());
            if (res.statusCode() != 200 || !node.hasNonNull("approval_key")) {
                throw new IllegalStateException("웹소켓 접속키 발급 실패 HTTP " + res.statusCode()
                        + " " + node.path("error_description").asText(node.path("msg1").asText("")));
            }
            return node.get("approval_key").asText();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("접속키 발급이 중단되었습니다", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("접속키 발급 요청 실패: " + e.getMessage(), e);
        }
    }

    private void subscribe(WebSocket ws, String approvalKey, List<Stock> stocks) {
        for (Stock s : stocks) {
            Map<String, Object> msg = Map.of(
                    "header", Map.of("approval_key", approvalKey, "custtype", "P", "tr_type", "1",
                            "content-type", "utf-8"),
                    "body", Map.of("input", Map.of("tr_id", KisTickParser.TR_ID, "tr_key", s.code())));
            try {
                ws.sendText(objectMapper.writeValueAsString(msg), true).join();
                Thread.sleep(50);   // 등록 요청을 한꺼번에 몰아 보내지 않는다
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                throw new IllegalStateException("구독 요청 실패 " + s.code() + ": " + e.getMessage(), e);
            }
        }
        log.info("체결가 구독 요청 {}건 보냄", stocks.size());
    }

    private class Listener implements WebSocket.Listener {
        private final CountDownLatch closed;
        private final StringBuilder buf = new StringBuilder();

        Listener(CountDownLatch closed) {
            this.closed = closed;
        }

        @Override
        public void onOpen(WebSocket ws) {
            log.info("한국투자증권 웹소켓 연결됨");
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String frame = buf.toString();
                buf.setLength(0);
                try {
                    handle(ws, frame);
                } catch (RuntimeException e) {
                    log.warn("프레임 처리 실패: {} / {}", e.getMessage(), frame.length() > 120 ? frame.substring(0, 120) : frame);
                }
            }
            ws.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            log.warn("한국투자증권 웹소켓 닫힘 code={} reason={}", statusCode, reason);
            closed.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.warn("한국투자증권 웹소켓 오류: {}", rootMessage(error));
            closed.countDown();
        }
    }

    private void handle(WebSocket ws, String frame) {
        if (KisTickParser.isDataFrame(frame)) {
            for (TickMessage t : KisTickParser.parse(frame, LocalDate.now(KST))) {
                publisher.publish(t);
                received.incrementAndGet();
            }
            return;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(frame);
        } catch (Exception e) {
            log.debug("알 수 없는 프레임: {}", frame);
            return;
        }
        String trId = node.path("header").path("tr_id").asText("");
        if ("PINGPONG".equals(trId)) {
            ws.sendText(frame, true);
            return;
        }
        JsonNode body = node.path("body");
        String rtCd = body.path("rt_cd").asText("");
        String trKey = node.path("header").path("tr_key").asText("");
        if (!"0".equals(rtCd)) {
            log.warn("구독 응답 오류 tr_key={} msg={}", trKey, body.path("msg1").asText(""));
        } else {
            log.debug("구독 응답 tr_key={} msg={}", trKey, body.path("msg1").asText(""));
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
