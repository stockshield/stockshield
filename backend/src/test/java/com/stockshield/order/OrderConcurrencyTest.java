package com.stockshield.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import com.stockshield.support.LedgerTestSupport;

/**
 * 주문 원장의 동시성·멱등 (TRD_LED_001 #2·구현방안 '테스트' (2)·(3), TRD_ORD_001 #4, SYS_CORE_001 #7).
 * 실제 PostgreSQL에서 실제 여러 스레드로 돌리고, 테스트 메서드에 @Transactional을 붙이지 않는다.
 * 계좌 락(AccountLocker)이나 락 안의 멱등키 재확인(OrderLedger)을 빼면 이 테스트가 실패한다.
 */
class OrderConcurrencyTest extends LedgerTestSupport {

    private static final int THREADS = 10;

    @Autowired DataSource dataSource;

    @Test
    @DisplayName("같은 멱등키 10건을 동시에 보내면 1건만 체결되고 10건 모두 같은 응답을 받는다")
    void sameKeyTenTimesAtOnce() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        putPriceNow(SAMSUNG, 70_000);
        Map<String, Object> request = orderBody(SAMSUNG, "BUY", 10, newKey());

        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            calls.add(() -> mockMvc.perform(orderRequest(t, request)).andReturn());
        }
        List<MvcResult> results = concurrently(calls);

        assertThat(results).allSatisfy(r -> assertThat(r.getResponse().getStatus()).isEqualTo(201));
        assertThat(results.stream().map(LedgerTestSupport::content).distinct()).hasSize(1);
        assertThat(results.stream()
                .filter(r -> "true".equals(r.getResponse().getHeader("Idempotency-Replayed"))).count())
                .isEqualTo(THREADS - 1);
        assertThat(orderCount(t.acntNo())).isEqualTo(1);
        assertThat(tradeCount(t.acntNo())).isEqualTo(1);
        assertThat(dpsBal(t.acntNo())).isEqualTo(PKG_10M - 700_000L);
        assertThat(hldQty(t.acntNo(), SAMSUNG)).isEqualTo(10);
    }

    @Test
    @DisplayName("예수금 1,000만 원에 300만 원 매수 10건을 동시에 보내면 3건만 체결되고 예수금은 음수가 되지 않는다")
    void concurrentBuysAtBalanceBoundary() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        putPriceNow(SAMSUNG, 1_000_000);

        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            calls.add(() -> mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "BUY", 3, newKey()))).andReturn());
        }
        List<MvcResult> results = concurrently(calls);

        assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 201).count()).isEqualTo(3);
        assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 422).count()).isEqualTo(7);
        assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 422).map(LedgerTestSupport::content))
                .allSatisfy(body -> assertThat(body).contains("INSUFFICIENT_BALANCE"));
        // 예수금·보유 수량이 체결 내역 합계와 맞는다
        assertThat(dpsBal(t.acntNo())).isEqualTo(1_000_000L).isEqualTo(expectedDpsBal(t.acntNo()));
        assertThat(hldQty(t.acntNo(), SAMSUNG)).isEqualTo(9).isEqualTo(expectedHldQty(t.acntNo(), SAMSUNG));
        assertThat(tradeCount(t.acntNo())).isEqualTo(3);
        assertThat(orderCount(t.acntNo())).isEqualTo(THREADS);
    }

    @Test
    @DisplayName("계좌 락을 2초 넘게 기다리면 503 LOCK_TIMEOUT(Retry-After: 1)이고 아무것도 저장하지 않아 같은 키로 다시 보낼 수 있다")
    void lockWaitOverTwoSeconds() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        putPriceNow(SAMSUNG, 70_000);
        Map<String, Object> request = orderBody(SAMSUNG, "BUY", 1, newKey());

        // 다른 트랜잭션이 이 계좌를 잡고 놓지 않는 상황
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement ps = holder.prepareStatement(
                    "SELECT acnt_no FROM account WHERE acnt_no = ? FOR NO KEY UPDATE")) {
                ps.setString(1, t.acntNo());
                ps.executeQuery();
            }
            long started = System.nanoTime();
            mockMvc.perform(orderRequest(t, request))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("LOCK_TIMEOUT"))
                    .andExpect(header().string("Retry-After", "1"));
            Duration waited = Duration.ofNanos(System.nanoTime() - started);
            assertThat(waited).isBetween(Duration.ofMillis(1_900), Duration.ofSeconds(10));
            assertThat(orderCount(t.acntNo())).isZero();
            holder.rollback();
        }

        mockMvc.perform(orderRequest(t, request))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotency-Replayed"));
        assertThat(orderCount(t.acntNo())).isEqualTo(1);
        assertThat(dpsBal(t.acntNo())).isEqualTo(PKG_10M - 70_000L);
    }
}
