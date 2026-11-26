package com.stockshield.reconcile;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 정기 대사: 평일 16:00(Asia/Seoul) (ADM_REC_001 #3·구현방안).
 * 다른 실행이 진행 중이면 1분 뒤로 다시 예약해 최대 10회 재시도하고, 그래도 못 하면 SCHEDULED·FAILED를 남김.
 * 스케줄러 스레드를 붙잡고 기다리지 않음(만료 정리·뉴스 수집과 스레드를 나눠 씀, 연결 합의서 3절).
 */
@Component
public class ReconcileScheduler {

    static final int MAX_RETRIES = 10;
    static final Duration RETRY_INTERVAL = Duration.ofMinutes(1);
    private static final Logger log = LoggerFactory.getLogger(ReconcileScheduler.class);

    enum Outcome { RAN, RETRY_SCHEDULED, GAVE_UP }

    private final ReconcileService reconcileService;
    private final ObjectProvider<TaskScheduler> taskScheduler;
    private final Clock clock;

    // WebSocket 브로커도 TaskScheduler 빈을 만들어 이름으로 고름. 테스트(스케줄링 꺼짐)에서는 빈이 없음
    public ReconcileScheduler(ReconcileService reconcileService,
                              @Qualifier("taskScheduler") ObjectProvider<TaskScheduler> taskScheduler, Clock clock) {
        this.reconcileService = reconcileService;
        this.taskScheduler = taskScheduler;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Asia/Seoul")
    public void runScheduled() {
        attempt(0);
    }

    /** @param retryNo 0이면 16:00의 첫 시도 */
    Outcome attempt(int retryNo) {
        if (reconcileService.run(ReconcileRun.SCHEDULED, null).isPresent()) {
            return Outcome.RAN;
        }
        if (retryNo < MAX_RETRIES) {
            scheduleRetry(retryNo + 1);
            return Outcome.RETRY_SCHEDULED;
        }
        reconcileService.recordScheduledGaveUp();
        return Outcome.GAVE_UP;
    }

    private void scheduleRetry(int retryNo) {
        TaskScheduler scheduler = taskScheduler.getIfAvailable();
        if (scheduler == null) {
            log.warn("스케줄러가 없어 정기 대사 재시도를 예약하지 못함 retryNo={}", retryNo);
            return;
        }
        log.info("다른 대사 실행이 진행 중이라 정기 대사를 1분 뒤 다시 시도함 retryNo={}", retryNo);
        scheduler.schedule(() -> attempt(retryNo), clock.instant().plus(RETRY_INTERVAL));
    }
}
