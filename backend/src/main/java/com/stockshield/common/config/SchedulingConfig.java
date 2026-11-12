package com.stockshield.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * @Scheduled 작업(원장 대사, PENDING 만료 정리, 뉴스 수집)을 켠다. stockshield.scheduling.enabled=false면 모두 꺼진다(테스트용).
 *
 * 스케줄러 빈은 여기서 직접 만든다. WebSocket 메시지 브로커가 TaskScheduler 빈(messageBrokerTaskScheduler)을 만들면
 * Spring Boot의 자동 스케줄러는 만들어지지 않아 spring.task.scheduling.pool.size가 무시되고, @Scheduled가 브로커 스레드에서 돈다.
 * TaskScheduler 빈이 둘이면 @Scheduled는 이름이 taskScheduler인 빈을 쓴다.
 * 스레드가 4개라 원장 대사의 재시도가 만료 정리·뉴스 수집을 막지 않는다.
 * (ADM_REC_001 #3, FDS_LOG_001 구현방안, NEWS_COL_001 #1, 연결 합의서 3절)
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "stockshield.scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {

    static final int POOL_SIZE = 4;

    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(POOL_SIZE);
        scheduler.setThreadNamePrefix("scheduler-");
        return scheduler;
    }
}
