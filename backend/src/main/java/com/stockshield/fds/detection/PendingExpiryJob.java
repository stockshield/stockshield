package com.stockshield.fds.detection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 1분마다 5분이 지난 인증 대기(PENDING) 기록을 FAIL로 확정한다 (FDS_LOG_001 #5·구현방안).
 * 사용자가 인증 도중 떠나도 기록이 대기 상태로 남지 않게 한다.
 * 출금 재요청이 같은 행을 FOR NO KEY UPDATE로 잡고 있으면 그 트랜잭션이 끝날 때까지 기다렸다가,
 * 이미 결과가 바뀐 행은 조건(rslt_cd = 'PENDING')에 걸리지 않아 건너뛴다.
 * 스레드를 붙잡고 기다리지 않는다(실패하면 다음 예약에서 다시 한다, 합의서 3절).
 */
@Component
public class PendingExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(PendingExpiryJob.class);

    private final DetectionRecorder recorder;
    private final TransactionTemplate transactionTemplate;

    public PendingExpiryJob(DetectionRecorder recorder, TransactionTemplate transactionTemplate) {
        this.recorder = recorder;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void run() {
        try {
            expireNow();
        } catch (RuntimeException e) {
            log.warn("인증 대기 만료 정리 실패, 다음 주기에 다시 시도", e);
        }
    }

    /** 만료 정리 한 번. 테스트에서 직접 부른다 */
    public int expireNow() {
        Integer expired = transactionTemplate.execute(status -> recorder.expirePending());
        int count = expired == null ? 0 : expired;
        if (count > 0) {
            log.info("인증 대기 {}건을 만료(FAIL)로 확정", count);
        }
        return count;
    }
}
