package com.stockshield.collector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.stockshield.collector.CollectorProperties.Mode;
import com.stockshield.collector.kis.KisRealtimeCollector;

import jakarta.annotation.PreDestroy;

/**
 * 기동하면 수집 방식을 정해 별도 스레드에서 돌린다.
 * collector.mode가 비어 있으면 DEMO_MODE=true일 때 재생, 아니면 한국투자증권 실시간이다(MKT_TICK_001 #2).
 */
@Component
public class CollectorRunner {

    private static final Logger log = LoggerFactory.getLogger(CollectorRunner.class);

    private final CollectorProperties props;
    private final KisRealtimeCollector kis;
    private final SimulatedTickSource simulated;
    private Thread worker;

    public CollectorRunner(CollectorProperties props, KisRealtimeCollector kis, SimulatedTickSource simulated) {
        this.props = props;
        this.kis = kis;
        this.simulated = simulated;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Mode mode = resolveMode();
        log.info("시세 수집 방식: {}", mode);
        Runnable task = switch (mode) {
            case KIS -> kis::runForever;
            case REPLAY -> simulated::replayForever;
            case MOCK -> simulated::mockForever;
        };
        worker = new Thread(task, "collector-" + mode.name().toLowerCase());
        worker.setDaemon(false);
        worker.start();
    }

    Mode resolveMode() {
        if (props.mode() != null && !props.mode().isBlank()) {
            return Mode.valueOf(props.mode().trim().toUpperCase());
        }
        if (props.demoMode()) {
            return Mode.REPLAY;
        }
        if (!props.kis().hasKey()) {
            log.warn("한국투자증권 키가 없어 가짜 시세(MOCK)로 수집합니다");
            return Mode.MOCK;
        }
        return Mode.KIS;
    }

    @PreDestroy
    public void stop() {
        kis.stop();
        simulated.stop();
        if (worker != null) {
            worker.interrupt();
        }
    }
}
