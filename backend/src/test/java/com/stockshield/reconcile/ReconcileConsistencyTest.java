package com.stockshield.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 원장 대사와 주문·출금이 서로 막지 않고, 대사가 거짓 불일치를 내지 않음
 * (TRD_LED_001 #2·구현방안 '테스트' (6), ADM_REC_001 #2, 요구사항명세서 부록 #2).
 * 실제 PostgreSQL에서 실제 여러 스레드로 돌리고 테스트 메서드에 @Transactional을 붙이지 않음.
 */
class ReconcileConsistencyTest extends ReconcileTestSupport {

    /** 계좌 락 대기 상한(2초)보다 짧게 기다려, 기다렸다가 통과한 경우도 실패로 잡음 */
    private static final long NO_WAIT_MS = 1_500;

    @Autowired ReconcileRepository repository;
    @Autowired ReconcileService reconcileService;
    @Autowired PlatformTransactionManager txManager;

    @Test
    @DisplayName("대사 결과를 저장하는 중(계좌를 참조하는 행을 넣고 커밋 전)에도 그 계좌의 출금·주문이 락 대기 없이 처리된다")
    void ledgerIsNotBlockedWhileResultsAreBeingSaved() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        putPriceNow(SAMSUNG, 70_000);
        long runNo = repository.startRunning("20261116", ReconcileRun.MANUAL, t.member().mbrNo(), MARKET_OPEN)
                .orElseThrow();
        ExecutorService other = Executors.newSingleThreadExecutor();
        List<Integer> statuses = new ArrayList<>();
        try {
            new TransactionTemplate(txManager).executeWithoutResult(tx -> {
                // 외래 키 확인으로 이 계좌 행에 FOR KEY SHARE가 걸린 채 트랜잭션이 열려 있음
                repository.insertResults(runNo, List.of(
                        new ReconcileItem(t.acntNo(), ReconcileItem.DEPOSIT, null, PKG_10M, PKG_10M, false)), MARKET_OPEN);
                statuses.add(within(other.submit(() ->
                        mockMvc.perform(withdrawRequest(t, withdrawBody(100_000, newKey(), null))).andReturn())));
                statuses.add(within(other.submit(() ->
                        mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "BUY", 1, newKey()))).andReturn())));
                tx.setRollbackOnly();
            });
        } finally {
            other.shutdownNow();
        }

        assertThat(statuses).containsExactly(201, 201);
        assertThat(dpsBal(t.acntNo())).isEqualTo(PKG_10M - 100_000 - 70_000).isEqualTo(expectedDpsBal(t.acntNo()));
    }

    private static int within(Future<MvcResult> f) {
        try {
            return f.get(NO_WAIT_MS, TimeUnit.MILLISECONDS).getResponse().getStatus();
        } catch (TimeoutException e) {
            throw new AssertionError("대사 저장 트랜잭션 때문에 계좌 락을 기다림", e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("대사가 도는 동안 같은 계좌에 출금·주문이 동시에 들어와도 그 계좌는 모든 실행에서 일치(N)로 나온다")
    void noFalseMismatchUnderConcurrentLedgerWrites() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        putPriceNow(SAMSUNG, 70_000);
        long adminMbrNo = createAdmin().mbrNo();

        AtomicBoolean writing = new AtomicBoolean(true);
        List<Long> runs = new CopyOnWriteArrayList<>();
        ExecutorService reconciler = Executors.newSingleThreadExecutor();
        Future<?> loop = reconciler.submit(() -> {
            do {
                reconcileService.run(ReconcileRun.MANUAL, adminMbrNo).ifPresent(r -> runs.add(r.rcclRunNo()));
            } while (writing.get());
        });
        try {
            List<Callable<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                calls.add(() -> mockMvc.perform(withdrawRequest(t, withdrawBody(10_000, newKey(), null))).andReturn());
                calls.add(() -> mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "BUY", 1, newKey()))).andReturn());
            }
            List<MvcResult> results = concurrently(calls);
            assertThat(results).allSatisfy(r -> assertThat(r.getResponse().getStatus()).isEqualTo(201));
        } finally {
            writing.set(false);
            loop.get(60, TimeUnit.SECONDS);
            reconciler.shutdownNow();
        }

        assertThat(runs).isNotEmpty();
        for (long runNo : runs) {
            assertThat(runRow(runNo)).containsEntry("run_stat_cd", "DONE");
            List<Map<String, Object>> mine = resultsOf(runNo, t.acntNo());
            assertThat(mine).isNotEmpty().allSatisfy(row -> assertThat(row).containsEntry("diff_yn", "N"));
        }
        assertThat(dpsBal(t.acntNo())).isEqualTo(PKG_10M - 100_000 - 700_000).isEqualTo(expectedDpsBal(t.acntNo()));
    }
}
