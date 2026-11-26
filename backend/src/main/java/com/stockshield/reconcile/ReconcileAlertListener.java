package com.stockshield.reconcile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 대사 불일치·실패를 관제 화면(/topic/admin/reconcile)에 알림 (ADM_REC_001 #4, FDS_ALT_001 #7, 문서 수정 대기 목록 #25).
 * 전송이 실패해도 대사 결과는 그대로 둠. 놓친 경보는 관제 화면의 대사 이력 조회로 보완됨.
 */
@Component
public class ReconcileAlertListener {

    public static final String DESTINATION = "/topic/admin/reconcile";
    private static final Logger log = LoggerFactory.getLogger(ReconcileAlertListener.class);

    private final ObjectProvider<SimpMessageSendingOperations> messaging;

    public ReconcileAlertListener(ObjectProvider<SimpMessageSendingOperations> messaging) {
        this.messaging = messaging;
    }

    // 대사 서비스는 트랜잭션 밖에서 발행하므로 fallbackExecution으로 바로 실행함. 트랜잭션 안에서 발행되면 커밋 뒤에 실행됨
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onFinished(ReconcileFinishedEvent event) {
        ReconcileRun run = event.run();
        SimpMessageSendingOperations ops = messaging.getIfAvailable();
        if (ops == null) {
            log.warn("대사 경보를 보낼 브로커 없음 rcclRunNo={} runStatCd={}", run.rcclRunNo(), run.runStatCd());
            return;
        }
        try {
            ops.convertAndSend(DESTINATION, ReconcileService.runBody(run));
        } catch (RuntimeException e) {
            log.warn("대사 경보 전송 실패 rcclRunNo={}", run.rcclRunNo(), e);
        }
    }
}
