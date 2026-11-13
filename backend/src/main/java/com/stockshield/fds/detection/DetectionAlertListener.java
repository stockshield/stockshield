package com.stockshield.fds.detection;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.stockshield.fds.config.FdsExecutorConfig;

/**
 * 탐지 기록이 커밋된 뒤 관제 화면(/topic/admin/detections)으로 경보를 보낸다 (FDS_LOG_001 #3, FDS_ALT_001 #1).
 *
 * - 커밋 뒤에만 보낸다. 롤백된 출금의 기록이 경보로 나가지 않게 하기 위해서다.
 * - fdsExecutor에서 보내고 실패하면 최대 3회(1초·2초·4초 뒤) 다시 보낸다. 요청 스레드는 기다리지 않는다(합의서 2절 규칙 5).
 *   끝내 실패해도 관제 화면이 접속할 때 확인하지 않은 기록을 DB에서 다시 불러와 보완된다(FDS_ALT_001 #6).
 * - WebSocket 브로커가 아직 없으면(WebSocketConfig 전) 로그만 남긴다.
 * - 형식은 탐지 목록 항목('API_명세서' 23행) + aiStatCd (연결 합의서 v0.0.9 회신 8번). 긴급 여부는 rsltCd = LOCKED로 판단한다.
 */
@Component
public class DetectionAlertListener {

    public static final String DESTINATION = "/topic/admin/detections";
    static final long[] RETRY_DELAYS_MS = {1_000, 2_000, 4_000};

    private static final Logger log = LoggerFactory.getLogger(DetectionAlertListener.class);
    private static final DateTimeFormatter KST = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'+09:00'");

    private final ObjectProvider<SimpMessageSendingOperations> messaging;
    private final JdbcTemplate jdbcTemplate;

    public DetectionAlertListener(ObjectProvider<SimpMessageSendingOperations> messaging, JdbcTemplate jdbcTemplate) {
        this.messaging = messaging;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 트랜잭션 밖(만료 정리 작업은 쿼리마다 자동 커밋)에서 발행된 변경도 보낸다 */
    @Async(FdsExecutorConfig.FDS_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDetectionChanged(DetectionChangedEvent event) {
        send(event);
    }

    /** AI 설명 상태가 바뀐 기록을 DB에서 다시 읽어 보낸다 (FDS_AI_001: 결과를 관제 화면에 전달). 호출한 스레드에서 보낸다 */
    public void resend(long dtcSeq) {
        List<DetectionChangedEvent> rows = jdbcTemplate.query(
                "SELECT rule_cd, rslt_cd, acnt_no, reg_ts FROM fds_detection WHERE dtc_seq = ?",
                (rs, n) -> new DetectionChangedEvent(dtcSeq, DetectionRule.valueOf(rs.getString("rule_cd")),
                        DetectionResult.valueOf(rs.getString("rslt_cd")), rs.getString("acnt_no"),
                        rs.getObject("reg_ts", LocalDateTime.class)), dtcSeq);
        rows.forEach(this::send);
    }

    private void send(DetectionChangedEvent event) {
        SimpMessageSendingOperations ops = messaging.getIfAvailable();
        if (ops == null) {
            log.info("탐지 경보(브로커 없음) dtcSeq={} rule={} result={}",
                    event.dtcSeq(), event.rule(), event.result());
            return;
        }
        Map<String, Object> payload = payload(event, currentFlags(event.dtcSeq()));
        for (int attempt = 0; ; attempt++) {
            try {
                ops.convertAndSend(DESTINATION, payload);
                return;
            } catch (RuntimeException e) {
                if (attempt >= RETRY_DELAYS_MS.length) {
                    log.warn("탐지 경보 전송 실패(재시도 {}회 후 포기) dtcSeq={}", RETRY_DELAYS_MS.length, event.dtcSeq(), e);
                    return;
                }
                if (!sleep(RETRY_DELAYS_MS[attempt])) {
                    return;
                }
            }
        }
    }

    /** 커밋된 행의 확인 여부와 AI 상태. 읽지 못하면 새 기록의 기본값(N, REQUESTED)을 쓴다 */
    String[] currentFlags(long dtcSeq) {
        try {
            return jdbcTemplate.query("SELECT chk_yn, ai_stat_cd FROM fds_detection WHERE dtc_seq = ?",
                    rs -> rs.next() ? new String[] {rs.getString(1), rs.getString(2)} : DEFAULT_FLAGS, dtcSeq);
        } catch (RuntimeException e) {
            log.debug("탐지 기록 상태 조회 실패 dtcSeq={}: {}", dtcSeq, e.getMessage());
            return DEFAULT_FLAGS;
        }
    }

    private static final String[] DEFAULT_FLAGS = {"N", "REQUESTED"};

    /**
     * 목록 조회 API('API_명세서' 23행)의 항목 + aiStatCd. 마스킹한 아이디(mskLgnId)는 마스킹 규칙이 관리자 API에 있으므로
     * 여기서는 null로 보내고 화면이 목록 조회로 채운다
     */
    static Map<String, Object> payload(DetectionChangedEvent event, String[] flags) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("dtcSeq", event.dtcSeq());
        p.put("ruleCd", event.rule().name());
        p.put("mskLgnId", null);
        p.put("acntNo", event.acntNo());
        p.put("rsltCd", event.result().name());
        p.put("chkYn", flags[0]);
        p.put("regTs", event.regTs() == null ? null : event.regTs().format(KST));
        p.put("aiStatCd", flags[1]);
        return p;
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
