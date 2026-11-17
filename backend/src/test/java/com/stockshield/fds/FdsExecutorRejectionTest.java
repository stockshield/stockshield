package com.stockshield.fds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.transaction.PlatformTransactionManager;

import com.stockshield.common.trace.TraceIds;
import com.stockshield.fds.access.TrustedAccessStore;
import com.stockshield.fds.api.LedgerReader;
import com.stockshield.fds.api.OrderFilledEvent;
import com.stockshield.fds.detection.DetectionAlertListener;
import com.stockshield.fds.detection.DetectionChangedEvent;
import com.stockshield.fds.detection.DetectionRecorder;
import com.stockshield.fds.detection.DetectionResult;
import com.stockshield.fds.detection.DetectionRule;
import com.stockshield.fds.trade.NewIpTradeRecorder;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * fdsExecutor 대기열이 가득 찼을 때(부하 테스트 11/17: 50명 연속 주문에서 200칸이 참)
 * 요청 스레드로 예외가 나가지 않고, 오류 내역 없이 주문번호·거래추적번호가 든 경고 한 줄만 남는지 본다.
 */
class FdsExecutorRejectionTest {

    private static final Executor FULL = task -> {
        throw new TaskRejectedException("대기열 가득 참");
    };

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void attach() {
        logs.start();
        ((Logger) LoggerFactory.getLogger(NewIpTradeRecorder.class)).addAppender(logs);
        ((Logger) LoggerFactory.getLogger(DetectionAlertListener.class)).addAppender(logs);
        MDC.put(TraceIds.MDC_KEY, "trace-123");
    }

    @AfterEach
    void detach() {
        ((Logger) LoggerFactory.getLogger(NewIpTradeRecorder.class)).detachAppender(logs);
        ((Logger) LoggerFactory.getLogger(DetectionAlertListener.class)).detachAppender(logs);
        MDC.clear();
    }

    private List<ILoggingEvent> warnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    @Test
    @DisplayName("새 IP 거래 판단이 거부되면 예외 없이 경고 한 줄(주문번호·거래추적번호, 오류 내역 없음)")
    void newIpTradeRejected() {
        LedgerReader ledger = mock(LedgerReader.class);
        NewIpTradeRecorder recorder = new NewIpTradeRecorder(ledger, mock(TrustedAccessStore.class),
                mock(DetectionRecorder.class), mock(JdbcTemplate.class), FULL, mock(PlatformTransactionManager.class));

        assertThatCode(() -> recorder.onOrderFilled(new OrderFilledEvent("ORD-20261117-000001")))
                .doesNotThrowAnyException();

        assertThat(warnings()).singleElement().satisfies(e -> {
            assertThat(e.getFormattedMessage()).contains("ORD-20261117-000001").contains("trace-123");
            assertThat(e.getThrowableProxy()).as("오류 내역(스택)을 찍지 않음").isNull();
        });
        verifyNoInteractions(ledger);
    }

    @Test
    @DisplayName("탐지 경보가 거부되면 예외 없이 경고 한 줄(탐지번호·거래추적번호, 오류 내역 없음)")
    void detectionAlertRejected() {
        SimpMessageSendingOperations ops = mock(SimpMessageSendingOperations.class);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ops", ops);
        DetectionAlertListener listener = new DetectionAlertListener(
                beans.getBeanProvider(SimpMessageSendingOperations.class), mock(JdbcTemplate.class), FULL);

        assertThatCode(() -> listener.onDetectionChanged(new DetectionChangedEvent(77L, DetectionRule.TRADE_NEW_IP,
                DetectionResult.LOGGED, "1001-01-000001", LocalDateTime.of(2026, 11, 17, 10, 0))))
                .doesNotThrowAnyException();

        assertThat(warnings()).singleElement().satisfies(e -> {
            assertThat(e.getFormattedMessage()).contains("dtcSeq=77").contains("trace-123");
            assertThat(e.getThrowableProxy()).isNull();
        });
        verifyNoInteractions(ops);
    }
}
