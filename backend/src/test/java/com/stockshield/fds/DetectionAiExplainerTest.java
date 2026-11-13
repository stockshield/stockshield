package com.stockshield.fds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockshield.common.error.ApiException;
import com.stockshield.common.error.ErrorCode;
import com.stockshield.fds.ai.AiServerClient;
import com.stockshield.fds.ai.DetectionAiExplainer;
import com.stockshield.fds.api.RequestMeta;
import com.stockshield.fds.detection.DetectionAlertListener;
import com.stockshield.fds.detection.DetectionChangedEvent;
import com.stockshield.fds.detection.DetectionRecorder;
import com.stockshield.fds.detection.DetectionRecorder.NewDetection;
import com.stockshield.fds.detection.DetectionResult;
import com.stockshield.fds.detection.DetectionRule;
import com.stockshield.support.IntegrationTestSupport;
import com.sun.net.httpserver.HttpServer;

/**
 * 탐지 사유 AI 설명 (FDS_AI_001 #3·#4, API 명세서 'API_명세서' 26행). 가짜 AI 서버(JDK HttpServer)로 확인한다.
 * 컨텍스트의 빈은 테스트 설정(stockshield.ai.enabled=false)으로 AI를 부르지 않으므로, 켠 인스턴스를 직접 만든다.
 * 실행기는 같은 스레드에서 바로 실행해 결과를 기다리지 않고 확인한다.
 */
class DetectionAiExplainerTest extends IntegrationTestSupport {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired DetectionAlertListener alerts;

    HttpServer server;
    final List<JsonNode> received = new ArrayList<>();
    final AtomicInteger status = new AtomicInteger(200);
    final AtomicLong delayMs = new AtomicLong();
    DetectionRecorder recorder;
    DetectionAiExplainer explainer;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/ai/detection-reason", ex -> {
            received.add(objectMapper.readTree(ex.getRequestBody()));
            sleep(delayMs.get());
            byte[] body = (status.get() == 200
                    ? "{\"aiRsnCont\":\"24시간 누적 출금이 기준을 넘었습니다.\",\"aiChkCont\":\"- 수취 계좌 확인\"}"
                    : "{\"code\":\"AI_UNAVAILABLE\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status.get(), body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        AiServerClient client = new AiServerClient(baseUrl, Duration.ofMillis(800), Duration.ofSeconds(1));
        explainer = new DetectionAiExplainer(jdbcTemplate, objectMapper, client, alerts, Runnable::run, true);
        recorder = new DetectionRecorder(jdbcTemplate, objectMapper, eventPublisher, new TestClock());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("결과가 확정된 기록은 AI 설명을 받아 DONE으로 저장하고, 개인정보 키는 보내지 않는다")
    void doneAndScrubbed() {
        long dtcSeq = detection(DetectionResult.LOCKED);

        explainer.onDetectionChanged(event(dtcSeq, DetectionResult.LOCKED));

        assertThat(received).hasSize(1);
        JsonNode req = received.get(0);
        assertThat(req.path("ruleCd").asText()).isEqualTo("WITHDRAW_LARGE");
        assertThat(req.path("rsltCd").asText()).isEqualTo("LOCKED");
        JsonNode facts = req.path("facts");
        assertThat(facts.path("wdrAmt").asLong()).isEqualTo(6_000_000L);
        assertThat(facts.path("nested").path("x").asInt()).isEqualTo(1);
        assertThat(facts.has("reqHash")).isFalse();
        assertThat(facts.has("acntNo")).isFalse();
        assertThat(facts.path("nested").has("conn_ip")).isFalse();
        assertThat(req.toString()).doesNotContain("10.0.0.7").doesNotContain("dvc-secret");

        Map<String, Object> row = row(dtcSeq);
        assertThat(row.get("ai_stat_cd")).isEqualTo("DONE");
        assertThat(row.get("ai_rsn_cont")).isEqualTo("24시간 누적 출금이 기준을 넘었습니다.");
        assertThat(row.get("ai_chk_cont")).isEqualTo("- 수취 계좌 확인");
    }

    @Test
    @DisplayName("인증 대기(PENDING)는 부르지 않고 REQUESTED로 둔다")
    void pendingSkipped() {
        long dtcSeq = detection(DetectionResult.PENDING);

        explainer.onDetectionChanged(event(dtcSeq, DetectionResult.PENDING));

        assertThat(received).isEmpty();
        assertThat(row(dtcSeq).get("ai_stat_cd")).isEqualTo("REQUESTED");
    }

    @Test
    @DisplayName("AI 서버 오류(500)나 시간 초과면 FAILED, 설명은 비워 둔다")
    void failedOnErrorAndTimeout() {
        status.set(500);
        long a = detection(DetectionResult.FAIL);
        explainer.onDetectionChanged(event(a, DetectionResult.FAIL));
        assertThat(row(a).get("ai_stat_cd")).isEqualTo("FAILED");
        assertThat(row(a).get("ai_rsn_cont")).isNull();

        status.set(200);
        delayMs.set(1_500);   // 읽기 제한 800ms 초과
        long b = detection(DetectionResult.PASS);
        explainer.onDetectionChanged(event(b, DetectionResult.PASS));
        assertThat(row(b).get("ai_stat_cd")).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("재요청: FAILED만 다시 부르고 DONE이 되며, 그 밖은 409, 없는 번호는 404")
    void retry() {
        status.set(500);
        long dtcSeq = detection(DetectionResult.LOGGED);
        explainer.onDetectionChanged(event(dtcSeq, DetectionResult.LOGGED));
        assertThat(row(dtcSeq).get("ai_stat_cd")).isEqualTo("FAILED");

        status.set(200);
        explainer.retry(dtcSeq);
        assertThat(row(dtcSeq).get("ai_stat_cd")).isEqualTo("DONE");
        assertThat(received).hasSize(2);

        assertThatThrownBy(() -> explainer.retry(dtcSeq))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.AI_RETRY_NOT_ALLOWED));
        assertThatThrownBy(() -> explainer.retry(Long.MAX_VALUE))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DETECTION_NOT_FOUND));
    }

    @Test
    @DisplayName("이미 끝난 기록(DONE)은 같은 이벤트가 다시 와도 AI를 다시 부르지 않는다")
    void noDuplicateCall() {
        long dtcSeq = detection(DetectionResult.PASS);
        explainer.onDetectionChanged(event(dtcSeq, DetectionResult.PASS));
        explainer.onDetectionChanged(event(dtcSeq, DetectionResult.PASS));
        assertThat(received).hasSize(1);
    }

    @Test
    @DisplayName("꺼져 있으면(stockshield.ai.enabled=false) 부르지 않는다")
    void disabled() {
        AiServerClient client = new AiServerClient("http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        DetectionAiExplainer off = new DetectionAiExplainer(jdbcTemplate, objectMapper, client, alerts,
                Runnable::run, false);
        long dtcSeq = detection(DetectionResult.PASS);
        off.onDetectionChanged(event(dtcSeq, DetectionResult.PASS));
        assertThat(received).isEmpty();
        assertThat(row(dtcSeq).get("ai_stat_cd")).isEqualTo("REQUESTED");
    }

    // ------------------------------------------------------------------ 도우미

    private long detection(DetectionResult result) {
        String lgnId = "a" + UUID.randomUUID().toString().substring(0, 8);
        long mbrNo = jdbcTemplate.queryForObject(
                "INSERT INTO member (lgn_id, lgn_pwd, mbr_nm, eml_addr) VALUES (?, 'hash', '테스트', 'test@example.com') "
                        + "RETURNING mbr_no", Long.class, lgnId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("wdrAmt", 6_000_000L);
        detail.put("reason", "SINGLE");
        detail.put("reqHash", "abc123");
        detail.put("acntNo", "10000000010");
        detail.put("nested", Map.of("x", 1, "conn_ip", "10.0.0.7", "dvcId", "dvc-secret"));
        return recorder.insert(new NewDetection(DetectionRule.WITHDRAW_LARGE, mbrNo, null, null, null,
                UUID.randomUUID().toString(), new RequestMeta("10.0.0.7", "dvc-secret", "JUnit"), result, detail));
    }

    private static DetectionChangedEvent event(long dtcSeq, DetectionResult result) {
        return new DetectionChangedEvent(dtcSeq, DetectionRule.WITHDRAW_LARGE, result, null, null);
    }

    private Map<String, Object> row(long dtcSeq) {
        return jdbcTemplate.queryForMap(
                "SELECT ai_stat_cd, ai_rsn_cont, ai_chk_cont FROM fds_detection WHERE dtc_seq = ?", dtcSeq);
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
