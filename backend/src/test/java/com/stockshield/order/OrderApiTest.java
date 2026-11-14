package com.stockshield.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;

import com.stockshield.fds.api.OrderFilledEvent;
import com.stockshield.support.LedgerTestSupport;

/**
 * 주문 API와 원장 반영 (TRD_ORD_001 #1~#6, TRD_LED_001 #1·#3·#4, API 명세서 'API_명세서' 17행).
 * 실제 FDS로 돈다. 동시성은 OrderConcurrencyTest, 롤백은 OrderRollbackTest, Redis 장애는 OrderRedisOutageTest.
 */
@RecordApplicationEvents
class OrderApiTest extends LedgerTestSupport {

    @Autowired ApplicationEvents events;
    @Autowired StringRedisTemplate redis;

    @Test
    @DisplayName("매수·매도가 예수금·보유 수량·주문·체결 내역에 함께 반영되고, 체결 내역에 거래 후 잔액이 남는다")
    void buyAndSellUpdateLedger() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        putPriceNow(SAMSUNG, 70_000);

        MvcResult buy = mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "BUY", 10, newKey())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tckCd").value(SAMSUNG))
                .andExpect(jsonPath("$.ordDvCd").value("BUY"))
                .andExpect(jsonPath("$.ordQty").value(10))
                .andExpect(jsonPath("$.ordPrc").value(70_000))
                .andExpect(jsonPath("$.trdAmt").value(700_000))
                .andExpect(jsonPath("$.ordStatCd").value("FILLED"))
                .andExpect(jsonPath("$.dpsBal").value(9_300_000))
                .andExpect(jsonPath("$.hldQty").value(10))
                .andExpect(jsonPath("$.regTs").value("2026-11-16T10:00:00+09:00"))
                .andReturn();
        String ordNo = body(buy).path("ordNo").asText();
        assertThat(ordNo).matches("O20261116\\d{9}");

        // 주문 행에 판단에 쓴 시세·요청 정보·거래추적번호가 남는다
        Map<String, Object> row = db.queryForMap("SELECT * FROM orders WHERE ord_no = ?", ordNo);
        assertThat(row.get("ord_stat_cd")).isEqualTo("FILLED");
        assertThat(row.get("rjct_rsn_cd")).isNull();
        assertThat(((java.sql.Timestamp) row.get("prc_ts")).toLocalDateTime()).isEqualTo(MARKET_OPEN);
        assertThat(row.get("conn_ip")).isEqualTo(t.member().ip());
        assertThat(row.get("dvc_id")).isEqualTo(t.member().deviceId());
        assertThat(row.get("trc_id")).isEqualTo(buy.getResponse().getHeader("X-Trace-Id"));

        putPriceNow(SAMSUNG, 80_000);
        mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "BUY", 10, newKey())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dpsBal").value(8_500_000))
                .andExpect(jsonPath("$.hldQty").value(20));
        assertThat(avgBuyPrc(t.acntNo(), SAMSUNG)).isEqualByComparingTo("75000.00");

        // 매도는 평균 단가를 그대로 두고, 다 팔면 0으로 되돌린다
        putPriceNow(SAMSUNG, 90_000);
        mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "SELL", 5, newKey())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.trdAmt").value(450_000))
                .andExpect(jsonPath("$.dpsBal").value(8_950_000))
                .andExpect(jsonPath("$.hldQty").value(15));
        assertThat(avgBuyPrc(t.acntNo(), SAMSUNG)).isEqualByComparingTo("75000.00");
        MvcResult sellAll = mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "SELL", 15, newKey())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dpsBal").value(10_300_000))
                .andExpect(jsonPath("$.hldQty").value(0))
                .andReturn();
        assertThat(avgBuyPrc(t.acntNo(), SAMSUNG)).isEqualByComparingTo("0.00");

        Map<String, Object> trade = db.queryForMap("SELECT * FROM trade_history WHERE ord_no = ?",
                body(sellAll).path("ordNo").asText());
        assertThat(((Number) trade.get("post_dps_bal")).longValue()).isEqualTo(10_300_000L);
        assertThat(((Number) trade.get("post_hld_qty")).longValue()).isZero();

        assertThat(dpsBal(t.acntNo())).isEqualTo(10_300_000L).isEqualTo(expectedDpsBal(t.acntNo()));
        assertThat(hldQty(t.acntNo(), SAMSUNG)).isZero().isEqualTo(expectedHldQty(t.acntNo(), SAMSUNG));
        assertThat(orderCount(t.acntNo())).isEqualTo(4);
        assertThat(tradeCount(t.acntNo())).isEqualTo(4);
    }

    @Test
    @DisplayName("평균 단가는 가중평균을 소수 둘째 자리에서 HALF_UP 한다: (3×100 + 5×101) ÷ 8 = 100.625 → 100.63")
    void averagePriceHalfUp() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        putPriceNow(HYNIX, 100);
        mockMvc.perform(orderRequest(t, orderBody(HYNIX, "BUY", 3, newKey()))).andExpect(status().isCreated());
        putPriceNow(HYNIX, 101);
        mockMvc.perform(orderRequest(t, orderBody(HYNIX, "BUY", 5, newKey()))).andExpect(status().isCreated());

        assertThat(avgBuyPrc(t.acntNo(), HYNIX)).isEqualByComparingTo("100.63");
    }

    @Test
    @DisplayName("업무 거절은 REJECTED와 거절 사유로 저장되고 원장은 바뀌지 않는다: 예수금 부족 422, 보유 수량 부족 422, 계좌 잠김 423")
    void rejectionsAreStoredAndLedgerUnchanged() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        putPriceNow(SAMSUNG, 70_000);

        MvcResult noMoney = mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "BUY", 1_000, newKey())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.detail.ordStatCd").value("REJECTED"))
                .andReturn();
        String ordNo = body(noMoney).path("detail").path("ordNo").asText();
        assertThat(body(noMoney).path("traceId").asText()).isEqualTo(noMoney.getResponse().getHeader("X-Trace-Id"));
        Map<String, Object> row = db.queryForMap("SELECT * FROM orders WHERE ord_no = ?", ordNo);
        assertThat(row.get("ord_stat_cd")).isEqualTo("REJECTED");
        assertThat(row.get("rjct_rsn_cd")).isEqualTo("INSUFFICIENT_BALANCE");
        // 판단에 쓴 시세는 거절에도 남는다
        assertThat(((Number) row.get("ord_prc")).longValue()).isEqualTo(70_000L);

        mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "SELL", 1, newKey())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_HOLDING"));

        // 예수금이 정확히 맞으면 체결된다(경계)
        putPriceNow(HYNIX, PKG_10M);
        mockMvc.perform(orderRequest(t, orderBody(HYNIX, "BUY", 1, newKey())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dpsBal").value(0));
        mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "BUY", 1, newKey())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));

        db.update("UPDATE account SET acnt_stat_cd = 'LOCKED' WHERE acnt_no = ?", t.acntNo());
        mockMvc.perform(orderRequest(t, orderBody(HYNIX, "SELL", 1, newKey())))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
                .andExpect(jsonPath("$.detail.ordStatCd").value("REJECTED"));

        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE acnt_no = ? AND ord_stat_cd = 'REJECTED'", Integer.class, t.acntNo()))
                .isEqualTo(4);
        assertThat(tradeCount(t.acntNo())).isEqualTo(1);
        assertThat(dpsBal(t.acntNo())).isZero();
        assertThat(hldQty(t.acntNo(), HYNIX)).isEqualTo(1);
    }

    @Test
    @DisplayName("장 운영 시간 경계: 15:29:59 체결, 15:30:00 거절, 08:59:59 거절, 09:00:00 체결, 토요일·휴장일 거절")
    void marketHoursBoundary() throws Exception {
        Trader t = newTraderAt(LocalDateTime.of(2026, 11, 16, 15, 29, 59));
        putPriceNow(SAMSUNG, 70_000);
        mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "BUY", 1, newKey()))).andExpect(status().isCreated());

        t = moveTo(t, LocalDateTime.of(2026, 11, 16, 15, 30, 0));
        putPriceNow(SAMSUNG, 70_000);
        MvcResult closed = mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "BUY", 1, newKey())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MARKET_CLOSED"))
                .andReturn();
        assertThat(db.queryForObject("SELECT rjct_rsn_cd FROM orders WHERE ord_no = ?", String.class,
                body(closed).path("detail").path("ordNo").asText())).isEqualTo("MARKET_CLOSED");

        t = moveTo(t, LocalDateTime.of(2026, 11, 17, 8, 59, 59));
        putPriceNow(SAMSUNG, 70_000);
        mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "BUY", 1, newKey())))
                .andExpect(jsonPath("$.code").value("MARKET_CLOSED"));

        t = moveTo(t, LocalDateTime.of(2026, 11, 17, 9, 0, 0));
        putPriceNow(SAMSUNG, 70_000);
        mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "BUY", 1, newKey()))).andExpect(status().isCreated());

        t = moveTo(t, LocalDateTime.of(2026, 11, 21, 10, 0));   // 토요일
        putPriceNow(SAMSUNG, 70_000);
        mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "BUY", 1, newKey())))
                .andExpect(jsonPath("$.code").value("MARKET_CLOSED"));

        t = moveTo(t, LocalDateTime.of(2026, 12, 25, 10, 0));   // 금요일, 휴장일(stockshield.market.holidays)
        putPriceNow(SAMSUNG, 70_000);
        mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "BUY", 1, newKey())))
                .andExpect(jsonPath("$.code").value("MARKET_CLOSED"));

        assertThat(tradeCount(t.acntNo())).isEqualTo(2);
        assertThat(orderCount(t.acntNo())).isEqualTo(6);
    }

    @Test
    @DisplayName("시세 지연 경계: 시세 시각이 정확히 60초 전이면 체결, 61초 전이면 PRICE_STALE. 시세가 없거나 해석할 수 없으면 PRICE_STALE(ordPrc 없음)")
    void staleOrMissingPrice() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);

        putPrice(SAMSUNG, 70_000, seoul(MARKET_OPEN.minusSeconds(60)));
        mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "BUY", 1, newKey()))).andExpect(status().isCreated());

        putPrice(SAMSUNG, 70_000, seoul(MARKET_OPEN.minusSeconds(61)));
        MvcResult stale = mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "BUY", 1, newKey())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRICE_STALE"))
                .andReturn();
        Map<String, Object> staleRow = db.queryForMap("SELECT ord_prc, prc_ts FROM orders WHERE ord_no = ?",
                body(stale).path("detail").path("ordNo").asText());
        assertThat(((Number) staleRow.get("ord_prc")).longValue()).isEqualTo(70_000L);
        assertThat(((java.sql.Timestamp) staleRow.get("prc_ts")).toLocalDateTime())
                .isEqualTo(MARKET_OPEN.minusSeconds(61));

        // 한 번도 시세가 들어오지 않은 종목
        String neverPriced = "058470";
        redis.delete("price:" + neverPriced);
        assertRejectedWithoutPrice(t, neverPriced);

        // 해석할 수 없는 값은 쓸 수 있는 시세가 없는 것으로 본다(Redis 연결은 됐으므로 503이 아니다)
        String broken = "028300";
        redis.opsForValue().set("price:" + broken, "not-json");
        assertRejectedWithoutPrice(t, broken);
        redis.opsForValue().set("price:" + broken,
                "{\"currentPrice\":\"70000\",\"prcTs\":\"" + seoul(MARKET_OPEN) + "\"}");
        assertRejectedWithoutPrice(t, broken);
        redis.opsForValue().set("price:" + broken, "{\"currentPrice\":70000,\"prcTs\":\"어제\"}");
        assertRejectedWithoutPrice(t, broken);
        redis.delete("price:" + broken);

        assertThat(tradeCount(t.acntNo())).isEqualTo(1);
    }

    private void assertRejectedWithoutPrice(Trader t, String tckCd) throws Exception {
        MvcResult r = mockMvc.perform(orderRequest(t, orderBody(tckCd, "BUY", 1, newKey())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRICE_STALE"))
                .andReturn();
        Map<String, Object> row = db.queryForMap("SELECT ord_prc, prc_ts FROM orders WHERE ord_no = ?",
                body(r).path("detail").path("ordNo").asText());
        assertThat(row.get("ord_prc")).isNull();
        assertThat(row.get("prc_ts")).isNull();
    }

    @Test
    @DisplayName("입력값이 틀리면 400, 없는 종목은 404이고 아무것도 저장하지 않는다")
    void invalidRequestsStoreNothing() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        putPriceNow(SAMSUNG, 70_000);

        for (Map<String, Object> bad : java.util.List.of(
                orderBody(SAMSUNG, "BUY", 0, newKey()),
                orderBody(SAMSUNG, "BUY", 1_000_001, newKey()),
                orderBody(SAMSUNG, "HOLD", 1, newKey()),
                orderBody(SAMSUNG, "BUY", 1, "not-a-uuid"),
                // UUID이지만 v4가 아님(버전 자리가 1)
                orderBody(SAMSUNG, "BUY", 1, "123e4567-e89b-12d3-a456-426614174000"),
                orderBody(null, "BUY", 1, newKey()),
                orderBody(SAMSUNG, "BUY", 1, null))) {
            mockMvc.perform(orderRequest(t, bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        // X-Device-Id가 있는데 UUID 형식이 아니면 400
        mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", bearer(t.tokens()))
                        .header("X-Device-Id", "my-phone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(orderBody(SAMSUNG, "BUY", 1, newKey()))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(orderRequest(t, orderBody("999999", "BUY", 1, newKey())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STOCK_NOT_FOUND"));

        assertThat(orderCount(t.acntNo())).isZero();
        assertThat(dpsBal(t.acntNo())).isEqualTo(PKG_10M);
    }

    @Test
    @DisplayName("X-Device-Id 없이 온 주문은 거절하지 않고 dvc_id를 NULL로 저장한다")
    void orderWithoutDeviceHeader() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        putPriceNow(SAMSUNG, 70_000);

        MvcResult r = mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", bearer(t.tokens()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(orderBody(SAMSUNG, "BUY", 1, newKey()))))
                .andExpect(status().isCreated())
                .andReturn();

        Map<String, Object> row = db.queryForMap("SELECT dvc_id, user_agt FROM orders WHERE ord_no = ?",
                body(r).path("ordNo").asText());
        assertThat(row.get("dvc_id")).isNull();
        assertThat(row.get("user_agt")).isEqualTo("-");
    }

    @Test
    @DisplayName("같은 키 재요청은 장이 닫히고 시세가 바뀐 뒤에도 처음 응답을 그대로 받는다(체결·거절). 내용이 다르면 409")
    void sameKeyReplaysFirstResult() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        putPriceNow(SAMSUNG, 70_000);
        String key = newKey();
        Map<String, Object> request = orderBody(SAMSUNG, "BUY", 10, key);

        MvcResult first = mockMvc.perform(orderRequest(t, request))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotency-Replayed"))
                .andReturn();

        t = moveTo(t, LocalDateTime.of(2026, 11, 16, 16, 0));
        putPriceNow(SAMSUNG, 99_000);
        MvcResult again = mockMvc.perform(orderRequest(t, request))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andReturn();
        assertThat(content(again)).isEqualTo(content(first));
        // 응답 헤더는 이번 요청의 번호, 주문 행에는 처음 번호
        assertThat(again.getResponse().getHeader("X-Trace-Id")).isNotEqualTo(first.getResponse().getHeader("X-Trace-Id"));
        assertThat(db.queryForObject("SELECT trc_id FROM orders WHERE acnt_no = ? AND idmp_key = ?", String.class,
                t.acntNo(), key)).isEqualTo(first.getResponse().getHeader("X-Trace-Id"));

        // 대소문자만 다른 같은 UUID는 같은 키
        mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "BUY", 10, key.toUpperCase())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"));

        mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "BUY", 11, key)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "SELL", 10, key)))
                .andExpect(status().isConflict());
        mockMvc.perform(orderRequest(t, orderBody(HYNIX, "BUY", 10, key)))
                .andExpect(status().isConflict());

        // 거절도 결과다: 장 마감 뒤 거절된 키를 다시 보내면 본문 traceId까지 같은 422
        Map<String, Object> rejectedRequest = orderBody(SAMSUNG, "BUY", 1, newKey());
        MvcResult rejected = mockMvc.perform(orderRequest(t, rejectedRequest))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MARKET_CLOSED"))
                .andReturn();
        t = moveTo(t, LocalDateTime.of(2026, 11, 17, 10, 0));
        putPriceNow(SAMSUNG, 70_000);
        MvcResult rejectedAgain = mockMvc.perform(orderRequest(t, rejectedRequest))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andReturn();
        assertThat(content(rejectedAgain)).isEqualTo(content(rejected));
        assertThat(body(rejectedAgain).path("traceId").asText())
                .isEqualTo(rejected.getResponse().getHeader("X-Trace-Id"));

        assertThat(orderCount(t.acntNo())).isEqualTo(2);
        assertThat(tradeCount(t.acntNo())).isEqualTo(1);
        assertThat(dpsBal(t.acntNo())).isEqualTo(9_300_000L);
    }

    @Test
    @DisplayName("체결된 주문만 OrderFilledEvent를 발행한다. 거절과 같은 키 재요청에는 발행하지 않는다")
    void onlyFilledOrderPublishesEvent() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        putPriceNow(SAMSUNG, 70_000);
        Map<String, Object> request = orderBody(SAMSUNG, "BUY", 1, newKey());

        MvcResult filled = mockMvc.perform(orderRequest(t, request)).andExpect(status().isCreated()).andReturn();
        mockMvc.perform(orderRequest(t, request)).andExpect(header().string("Idempotency-Replayed", "true"));
        mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "SELL", 999, newKey())))
                .andExpect(status().isUnprocessableEntity());

        assertThat(events.stream(OrderFilledEvent.class).map(OrderFilledEvent::ordNo))
                .containsExactly(body(filled).path("ordNo").asText());
    }

    private BigDecimal avgBuyPrc(String acntNo, String tckCd) {
        return db.queryForObject("SELECT avg_buy_prc FROM holding WHERE acnt_no = ? AND tck_cd = ?",
                BigDecimal.class, acntNo, tckCd);
    }
}
