package com.stockshield.fds.config;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * FDS의 커밋 후 작업(경보 전송, ③ 새 IP 거래 기록, AI 설명 요청)을 돌리는 실행기.
 * 모든 @Async에는 이 이름("fdsExecutor")을 적는다.
 *
 * - 큐가 가득 차면 작업을 거부하고 로그만 남긴다(AbortPolicy). CallerRunsPolicy는 쓰지 않는다:
 *   커밋 뒤 리스너를 부른 요청 스레드에서 작업이 돌아 주문·출금 응답이 늦어지기 때문이다(TRD_ORD_001 #6, SYS_CORE_001 #1).
 * - 요청 스레드의 MDC(거래추적번호 traceId)를 작업 스레드로 복사해, 커밋 뒤 작업이 실패해 로그만 남을 때도
 *   거래추적번호로 찾을 수 있게 한다(SYS_SEC_001 #6).
 * (연결 합의서 v0.0.8 3절 '비동기 실행기 규칙', 합의 7-3)
 */
@Configuration
public class FdsExecutorConfig {

    public static final String FDS_EXECUTOR = "fdsExecutor";

    static final int CORE_POOL_SIZE = 2;
    static final int MAX_POOL_SIZE = 4;
    static final int QUEUE_CAPACITY = 200;

    @Bean(name = FDS_EXECUTOR)
    public ThreadPoolTaskExecutor fdsExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("fds-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setTaskDecorator(new MdcCopyingDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    /** 작업을 맡긴 스레드의 MDC를 작업 스레드에 복사하고, 끝나면 원래대로 되돌린다 */
    static class MdcCopyingDecorator implements TaskDecorator {
        @Override
        public Runnable decorate(Runnable task) {
            Map<String, String> callerMdc = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (callerMdc == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(callerMdc);
                }
                try {
                    task.run();
                } finally {
                    if (previous == null) {
                        MDC.clear();
                    } else {
                        MDC.setContextMap(previous);
                    }
                }
            };
        }
    }
}
