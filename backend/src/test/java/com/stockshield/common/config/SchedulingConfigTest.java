package com.stockshield.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 스케줄러 설정 (ADM_REC_001 #3, FDS_LOG_001 구현방안, NEWS_COL_001 #1, 연결 합의서 3절)
 * - @Scheduled는 직접 만든 taskScheduler(풀 4)에서 돈다. WebSocket 브로커의 TaskScheduler 빈이 함께 있어도 같다
 * - stockshield.scheduling.enabled=false면 스케줄러와 @Scheduled가 모두 꺼진다(테스트용)
 */
class SchedulingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class, BrokerSchedulerAndProbe.class);

    @Test
    @DisplayName("TaskScheduler 빈이 둘이어도 @Scheduled는 이름이 taskScheduler인 풀 4 스케줄러에서 돈다")
    void scheduledRunsOnTaskSchedulerEvenWithBrokerScheduler() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(SchedulingConfig.class);
            assertThat(context.getBeansOfType(TaskScheduler.class)).containsKeys("taskScheduler", "messageBrokerTaskScheduler");
            // getPoolSize()는 지금 만들어진 스레드 수라서, 설정한 크기는 실행기의 core pool size로 확인한다
            assertThat(context.getBean("taskScheduler", ThreadPoolTaskScheduler.class)
                    .getScheduledThreadPoolExecutor().getCorePoolSize())
                    .isEqualTo(SchedulingConfig.POOL_SIZE);

            Probe probe = context.getBean(Probe.class);
            assertThat(probe.ran.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(probe.threadName.get()).startsWith("scheduler-");
        });
    }

    @Test
    @DisplayName("stockshield.scheduling.enabled=false면 스케줄러가 없고 @Scheduled 작업이 돌지 않는다")
    void schedulingCanBeTurnedOffByProperty() {
        runner.withPropertyValues("stockshield.scheduling.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(SchedulingConfig.class);
            assertThat(context).doesNotHaveBean("taskScheduler");

            Probe probe = context.getBean(Probe.class);
            assertThat(probe.ran.await(300, TimeUnit.MILLISECONDS)).isFalse();
        });
    }

    /** WebSocket 메시지 브로커가 만드는 것과 같은 이름의 두 번째 스케줄러와, 어느 스레드에서 도는지 기록하는 작업 */
    @Configuration(proxyBeanMethods = false)
    static class BrokerSchedulerAndProbe {

        @Bean
        ThreadPoolTaskScheduler messageBrokerTaskScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(2);
            scheduler.setThreadNamePrefix("broker-");
            return scheduler;
        }

        @Bean
        Probe probe() {
            return new Probe();
        }
    }

    static class Probe {

        final CountDownLatch ran = new CountDownLatch(1);
        final AtomicReference<String> threadName = new AtomicReference<>();

        @Scheduled(fixedDelay = 50)
        void run() {
            threadName.compareAndSet(null, Thread.currentThread().getName());
            ran.countDown();
        }
    }
}
