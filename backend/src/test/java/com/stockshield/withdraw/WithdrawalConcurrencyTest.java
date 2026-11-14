package com.stockshield.withdraw;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import com.stockshield.support.LedgerTestSupport;

/**
 * 출금 원장의 동시성·멱등 (TRD_LED_001 #2·구현방안 '테스트' (1)~(3), FDS_WDR_001 #1, TRD_WDR_001 #3, 요구사항명세서 부록 #4).
 * 실제 FDS(대량 출금 판단)와 함께 실제 PostgreSQL에서 실제 여러 스레드로 돌리고, 테스트 메서드에 @Transactional을 붙이지 않는다.
 * 계좌 락(AccountLocker)을 빼면 분할 출금이 서로의 금액을 보지 못해 함께 통과하고, 이 테스트가 실패한다.
 */
class WithdrawalConcurrencyTest extends LedgerTestSupport {

    private static long count(List<MvcResult> results, int status) {
        return results.stream().filter(r -> r.getResponse().getStatus() == status).count();
    }

    @Test
    @DisplayName("300만 원 2건을 동시에 보내면 1건만 출금되고 1건은 비밀번호를 요구받는다(428). 예수금은 300만 원만 줄어든다")
    void concurrentSplitWithdrawals() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);

        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            calls.add(() -> mockMvc.perform(withdrawRequest(t, withdrawBody(3_000_000, newKey(), null))).andReturn());
        }
        List<MvcResult> results = concurrently(calls);

        assertThat(count(results, 201)).isEqualTo(1);
        assertThat(count(results, 428)).isEqualTo(1);
        assertThat(dpsBal(t.acntNo())).isEqualTo(PKG_10M - 3_000_000L).isEqualTo(expectedDpsBal(t.acntNo()));
        assertThat(withdrawalCount(t.acntNo())).isEqualTo(1);
    }

    @Test
    @DisplayName("100만 원 10건을 동시에 보내면 누적 400만 원까지 4건만 출금되고 나머지 6건은 428이다")
    void tenSplitWithdrawalsStopAtThreshold() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);

        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            calls.add(() -> mockMvc.perform(withdrawRequest(t, withdrawBody(1_000_000, newKey(), null))).andReturn());
        }
        List<MvcResult> results = concurrently(calls);

        assertThat(count(results, 201)).isEqualTo(4);
        assertThat(count(results, 428)).isEqualTo(6);
        assertThat(dpsBal(t.acntNo())).isEqualTo(PKG_10M - 4_000_000L).isEqualTo(expectedDpsBal(t.acntNo()));
        assertThat(withdrawalCount(t.acntNo())).isEqualTo(4);
    }

    @Test
    @DisplayName("같은 멱등키 출금 10건을 동시에 보내면 1건만 출금되고 10건 모두 같은 응답을 받는다")
    void sameKeyTenTimesAtOnce() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        Map<String, Object> request = withdrawBody(1_000_000, newKey(), null);

        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            calls.add(() -> mockMvc.perform(withdrawRequest(t, request)).andReturn());
        }
        List<MvcResult> results = concurrently(calls);

        assertThat(count(results, 201)).isEqualTo(10);
        assertThat(results.stream().map(LedgerTestSupport::content).distinct()).hasSize(1);
        assertThat(results.stream()
                .filter(r -> "true".equals(r.getResponse().getHeader("Idempotency-Replayed"))).count()).isEqualTo(9);
        assertThat(withdrawalCount(t.acntNo())).isEqualTo(1);
        assertThat(dpsBal(t.acntNo())).isEqualTo(PKG_10M - 1_000_000L);
    }

    @Test
    @DisplayName("예수금 100만 원에 30만 원 출금 10건을 동시에 보내면 3건만 출금되고 예수금은 음수가 되지 않는다")
    void concurrentWithdrawalsAtBalanceBoundary() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        db.update("UPDATE account SET dps_bal = 1000000 WHERE acnt_no = ?", t.acntNo());

        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            calls.add(() -> mockMvc.perform(withdrawRequest(t, withdrawBody(300_000, newKey(), null))).andReturn());
        }
        List<MvcResult> results = concurrently(calls);

        assertThat(count(results, 201)).isEqualTo(3);
        assertThat(count(results, 422)).isEqualTo(7);
        assertThat(dpsBal(t.acntNo())).isEqualTo(100_000L);
        assertThat(db.queryForObject(
                "SELECT COALESCE(SUM(wdr_amt), 0) FROM withdrawal WHERE acnt_no = ? AND wdr_stat_cd = 'DONE'",
                Long.class, t.acntNo())).isEqualTo(900_000L);
    }

    @Test
    @DisplayName("같은 계좌에 매수·매도·출금 100건을 동시에 보내도 예수금과 보유 수량이 체결·출금 내역 합계와 맞는다")
    void hundredMixedOrdersAndWithdrawals() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        putPriceNow(SAMSUNG, 70_000);
        // 매도가 체결될 수 있게 먼저 20주를 사 둔다
        mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "BUY", 20, newKey()))).andReturn();

        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            switch (i % 4) {
                case 0 -> calls.add(() -> mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "BUY", 1, newKey())))
                        .andReturn());
                case 1 -> calls.add(() -> mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "SELL", 1, newKey())))
                        .andReturn());
                case 2 -> calls.add(() -> mockMvc.perform(withdrawRequest(t, withdrawBody(10_000, newKey(), null)))
                        .andReturn());
                default -> calls.add(() -> mockMvc.perform(withdrawRequest(t, withdrawBody(20_000, newKey(), null)))
                        .andReturn());
            }
        }
        List<MvcResult> results = concurrently(calls);

        // 체결·완료(201), 업무 거절(422), 락 대기 초과·연결 부족(503)만 나온다. 503은 아무것도 저장하지 않는다
        long stored = count(results, 201) + count(results, 422);
        assertThat(stored + count(results, 503)).isEqualTo(100);
        assertThat(count(results, 201)).isGreaterThanOrEqualTo(50);
        assertThat((long) orderCount(t.acntNo()) + withdrawalCount(t.acntNo())).isEqualTo(stored + 1);

        assertThat(dpsBal(t.acntNo())).isEqualTo(expectedDpsBal(t.acntNo()));
        assertThat(hldQty(t.acntNo(), SAMSUNG)).isEqualTo(expectedHldQty(t.acntNo(), SAMSUNG));
        assertThat(dpsBal(t.acntNo())).isNotNegative();
    }
}
