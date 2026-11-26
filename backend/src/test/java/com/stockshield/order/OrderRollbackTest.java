package com.stockshield.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;

import com.stockshield.fds.api.OrderFilledEvent;
import com.stockshield.support.LedgerTestSupport;

/**
 * 주문 트랜잭션의 원자성: 처리 중 예외가 나면 예수금·보유 종목·주문·체결 내역이 모두 되돌려진다
 * (TRD_LED_001 #3·구현방안 '테스트' (4)).
 *
 * 원장 반영과 주문·체결 내역 INSERT가 끝난 직후, 같은 트랜잭션 안에서 도는 동기 리스너가 예외를 던지게 한다.
 * 운영 코드에 실패 지점을 심지 않으려고 테스트 전용 리스너를 쓴다(이 클래스만 다른 컨텍스트).
 */
@Import(OrderRollbackTest.FailAfterLedgerWrite.class)
class OrderRollbackTest extends LedgerTestSupport {

    @TestConfiguration(proxyBeanMethods = false)
    static class FailAfterLedgerWrite {
        @Bean
        FailingListener failingListener() {
            return new FailingListener();
        }
    }

    public static class FailingListener {
        volatile boolean armed;

        @EventListener
        public void onFilled(OrderFilledEvent event) {
            if (armed) {
                throw new IllegalStateException("테스트: 원장 반영 직후 실패");
            }
        }
    }

    @Autowired FailingListener failing;

    @AfterEach
    void disarm() {
        failing.armed = false;
    }

    @Test
    @DisplayName("원장 반영 직후 예외가 나면 500이고 예수금·보유 종목·주문·체결 내역이 모두 처리 전 그대로다. 같은 키로 다시 보내면 처리된다")
    void failureAfterLedgerWriteRollsBackEverything() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        putPriceNow(SAMSUNG, 70_000);
        mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "BUY", 10, newKey()))).andExpect(status().isCreated());

        putPriceNow(SAMSUNG, 80_000);
        Map<String, Object> buy = orderBody(SAMSUNG, "BUY", 10, newKey());
        Map<String, Object> sell = orderBody(SAMSUNG, "SELL", 4, newKey());
        failing.armed = true;
        mockMvc.perform(orderRequest(t, buy))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        mockMvc.perform(orderRequest(t, sell))
                .andExpect(status().isInternalServerError());

        assertThat(dpsBal(t.acntNo())).isEqualTo(PKG_10M - 700_000L);
        assertThat(hldQty(t.acntNo(), SAMSUNG)).isEqualTo(10);
        assertThat(db.queryForObject("SELECT avg_buy_prc FROM holding WHERE acnt_no = ? AND tck_cd = ?",
                BigDecimal.class, t.acntNo(), SAMSUNG)).isEqualByComparingTo("70000.00");
        assertThat(orderCount(t.acntNo())).isEqualTo(1);
        assertThat(tradeCount(t.acntNo())).isEqualTo(1);

        // 결과가 저장되지 않았으므로 멱등키도 소모되지 않았다
        failing.armed = false;
        mockMvc.perform(orderRequest(t, buy))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotency-Replayed"))
                .andExpect(jsonPath("$.hldQty").value(20));
        assertThat(dpsBal(t.acntNo())).isEqualTo(PKG_10M - 1_500_000L).isEqualTo(expectedDpsBal(t.acntNo()));
    }

    @Test
    @DisplayName("처음 사는 종목에서 실패하면 보유 종목 행도 생기지 않는다")
    void failureOnFirstBuyLeavesNoHoldingRow() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        putPriceNow(HYNIX, 50_000);
        failing.armed = true;

        mockMvc.perform(orderRequest(t, orderBody(HYNIX, "BUY", 2, newKey())))
                .andExpect(status().isInternalServerError());

        assertThat(db.queryForObject("SELECT COUNT(*) FROM holding WHERE acnt_no = ?", Integer.class, t.acntNo()))
                .isZero();
        assertThat(dpsBal(t.acntNo())).isEqualTo(PKG_10M);
        assertThat(orderCount(t.acntNo())).isZero();
    }
}
