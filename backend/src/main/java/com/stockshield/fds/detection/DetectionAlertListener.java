package com.stockshield.fds.detection;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
 */
@Component
public class DetectionAlertListener {

    public static final String DESTINATION = "/topic/admin/detections";
    static final long[] RETRY_DELAYS_MS = {1_000, 2_000, 4_000};

    private static final Logger log = LoggerFactory.getLogger(DetectionAlertListener.class);
    private static final DateTimeFormatter KST = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'+09:00'");

    private final ObjectProvider<SimpMessageSendingOperations> messaging;

    public DetectionAlertListener(ObjectProvider<SimpMessageSendingOperations> messaging) {
        this.messaging = messaging;
    }

    @Async(FdsExecutorConfig.FDS_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDetectionChanged(DetectionChangedEvent event) {
        SimpMessageSendingOperations ops = messaging.getIfAvailable();
        if (ops == null) {
            log.info("탐지 경보(브로커 없음) dtcSeq={} rule={} result={} urgent={}",
                    event.dtcSeq(), event.rule(), event.result(), event.urgent());
            return;
        }
        Map<String, Object> payload = payload(event);
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

    /** 목록 조회 API('API_명세서' 23행)의 항목과 같은 이름을 쓴다. 회원 아이디는 FDS가 읽지 않으므로 화면이 목록에서 채운다 */
    static Map<String, Object> payload(DetectionChangedEvent event) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("dtcSeq", event.dtcSeq());
        p.put("ruleCd", event.rule().name());
        p.put("rsltCd", event.result().name());
        p.put("acntNo", event.acntNo());
        p.put("urgent", event.urgent());
        p.put("regTs", event.regTs() == null ? null : event.regTs().format(KST));
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
