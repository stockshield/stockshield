package com.stockshield.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.stockshield.support.LedgerTestSupport;

/** 내 계좌 조회 (TRD_ACC_001 #1~#3·#5, USR_AUTH_003 #4, API 명세서 'API_명세서' 18~20행) */
class AccountQueryApiTest extends LedgerTestSupport {

    @Autowired StringRedisTemplate redis;

    private MockHttpServletRequestBuilder me(String path, Trader t) {
        return get("/api/v1/accounts/me/" + path).header("Authorization", bearer(t.tokens()));
    }

    @Test
    @DisplayName("보유 종목이 없으면 평가·매입·손익이 0이고 수익률도 0이다(매입금액 0으로 나누지 않는다)")
    void emptyAccount() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);

        mockMvc.perform(me("summary", t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acntNo").value(t.acntNo()))
                .andExpect(jsonPath("$.acntStatCd").value("ACTIVE"))
                .andExpect(jsonPath("$.lockRsn").isEmpty())
                .andExpect(jsonPath("$.dpsBal").value(10_000_000))
                .andExpect(jsonPath("$.totalBuyAmt").value(0))
                .andExpect(jsonPath("$.totalEvalAmt").value(0))
                .andExpect(jsonPath("$.totalAssetAmt").value(10_000_000))
                .andExpect(jsonPath("$.totalPnl").value(0))
                .andExpect(jsonPath("$.yieldRt").value(0.0))
                .andExpect(jsonPath("$.priceDelayed").value(false));
        mockMvc.perform(me("holdings", t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(me("orders", t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    @DisplayName("평가금액은 수량 × 현재가, 매입금액은 수량 × 평균 단가를 원 미만 반올림, 수익률은 손익 ÷ 매입 × 100을 소수 둘째 자리 HALF_UP 한다")
    void valuation() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        // 하이닉스 8주, 평균 단가 100.63 (3×100 + 5×101 = 805원)
        putPriceNow(HYNIX, 100);
        mockMvc.perform(orderRequest(t, orderBody(HYNIX, "BUY", 3, newKey()))).andExpect(status().isCreated());
        putPriceNow(HYNIX, 101);
        mockMvc.perform(orderRequest(t, orderBody(HYNIX, "BUY", 5, newKey()))).andExpect(status().isCreated());
        // 삼성전자 10주, 평균 단가 70,000
        putPriceNow(SAMSUNG, 70_000);
        mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "BUY", 10, newKey()))).andExpect(status().isCreated());

        putPriceNow(HYNIX, 110);
        putPriceNow(SAMSUNG, 63_000);

        // 종목코드 순: 000660 하이닉스, 005930 삼성전자
        mockMvc.perform(me("holdings", t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].tckCd").value(HYNIX))
                .andExpect(jsonPath("$[0].tckNm").value("SK하이닉스"))
                .andExpect(jsonPath("$[0].hldQty").value(8))
                .andExpect(jsonPath("$[0].avgBuyPrc").value(100.63))
                .andExpect(jsonPath("$[0].currentPrice").value(110))
                .andExpect(jsonPath("$[0].evalAmt").value(880))
                // 매입금액 8 × 100.63 = 805.04 → 805원, 손익 75원, 수익률 75 ÷ 805 × 100 = 9.3167… → 9.32
                .andExpect(jsonPath("$[0].evalPnl").value(75))
                .andExpect(jsonPath("$[0].pnlRt").value(9.32))
                .andExpect(jsonPath("$[0].priceDelayed").value(false))
                .andExpect(jsonPath("$[1].tckCd").value(SAMSUNG))
                .andExpect(jsonPath("$[1].tckNm").value("삼성전자"))
                .andExpect(jsonPath("$[1].evalAmt").value(630_000))
                .andExpect(jsonPath("$[1].evalPnl").value(-70_000))
                .andExpect(jsonPath("$[1].pnlRt").value(-10.0));

        // 총 매입 805.04 + 700,000 → 700,805원, 총 평가 630,880원, 손익 −69,925원, 수익률 −9.9778… → −9.98
        MvcResult summary = mockMvc.perform(me("summary", t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dpsBal").value(9_299_195))
                .andExpect(jsonPath("$.totalBuyAmt").value(700_805))
                .andExpect(jsonPath("$.totalEvalAmt").value(630_880))
                .andExpect(jsonPath("$.totalAssetAmt").value(9_930_075))
                .andExpect(jsonPath("$.totalPnl").value(-69_925))
                .andExpect(jsonPath("$.priceDelayed").value(false))
                .andReturn();
        // 비율은 double이 아니라 소수 둘째 자리 숫자로 나간다
        assertThat(testJson.readTree(content(summary)).path("yieldRt").decimalValue())
                .isEqualByComparingTo(new BigDecimal("-9.98"));
        assertThat(content(summary)).contains("\"yieldRt\":-9.98");

        // 다 판 종목은 행이 남아 있어도 보유 종목에 나오지 않는다
        mockMvc.perform(orderRequest(t, orderBody(HYNIX, "SELL", 8, newKey()))).andExpect(status().isCreated());
        mockMvc.perform(me("holdings", t))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].tckCd").value(SAMSUNG));
    }

    @Test
    @DisplayName("시세 시각이 정확히 60초 전이면 지연이 아니고 61초 전이면 지연이다. 시세가 없는 종목은 매입금액으로 평가하고 지연으로 표시한다")
    void delayedOrMissingPrice() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        putPriceNow(SAMSUNG, 70_000);
        mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "BUY", 10, newKey()))).andExpect(status().isCreated());
        putPriceNow(HYNIX, 50_000);
        mockMvc.perform(orderRequest(t, orderBody(HYNIX, "BUY", 2, newKey()))).andExpect(status().isCreated());

        putPrice(SAMSUNG, 80_000, seoul(MARKET_OPEN.minusSeconds(60)));
        mockMvc.perform(me("summary", t)).andExpect(jsonPath("$.priceDelayed").value(false));

        putPrice(SAMSUNG, 80_000, seoul(MARKET_OPEN.minusSeconds(61)));
        mockMvc.perform(me("holdings", t))
                .andExpect(jsonPath("$[0].tckCd").value(HYNIX))
                .andExpect(jsonPath("$[0].priceDelayed").value(false))
                .andExpect(jsonPath("$[1].priceDelayed").value(true))
                // 지연된 시세라도 마지막 값으로 평가한다
                .andExpect(jsonPath("$[1].currentPrice").value(80_000))
                .andExpect(jsonPath("$[1].evalAmt").value(800_000));
        mockMvc.perform(me("summary", t)).andExpect(jsonPath("$.priceDelayed").value(true));

        redis.delete("price:" + HYNIX);
        mockMvc.perform(me("holdings", t))
                .andExpect(jsonPath("$[0].currentPrice").isEmpty())
                .andExpect(jsonPath("$[0].evalAmt").value(100_000))
                .andExpect(jsonPath("$[0].evalPnl").value(0))
                .andExpect(jsonPath("$[0].priceDelayed").value(true));
    }

    @Test
    @DisplayName("계좌가 잠겨 있으면 잠긴 이유를 보여 준다: TEMP_LOCKED는 출금 비밀번호 5회 오류, LOCKED는 관리자 잠금")
    void lockReason() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);

        db.update("UPDATE account SET acnt_stat_cd = 'TEMP_LOCKED', wdr_pwd_fail_cnt = 5 WHERE acnt_no = ?", t.acntNo());
        mockMvc.perform(me("summary", t))
                .andExpect(jsonPath("$.acntStatCd").value("TEMP_LOCKED"))
                .andExpect(jsonPath("$.lockRsn").value("출금 비밀번호 5회 오류"));

        db.update("UPDATE account SET acnt_stat_cd = 'LOCKED' WHERE acnt_no = ?", t.acntNo());
        mockMvc.perform(me("summary", t))
                .andExpect(jsonPath("$.acntStatCd").value("LOCKED"))
                .andExpect(jsonPath("$.lockRsn").value("관리자 잠금"));
    }

    @Test
    @DisplayName("주문 내역은 체결·거절을 최신순으로 보여 주고 상태로 걸러 볼 수 있다. 거절에는 사유와 안내 문구가 있고 체결금액은 없다")
    void orderHistory() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        putPriceNow(SAMSUNG, 70_000);
        String first = body(mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "BUY", 10, newKey()))).andReturn())
                .path("ordNo").asText();
        t = moveTo(t, MARKET_OPEN.plusSeconds(1));
        String second = body(mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "SELL", 999, newKey()))).andReturn())
                .path("detail").path("ordNo").asText();
        t = moveTo(t, MARKET_OPEN.plusSeconds(2));
        String third = body(mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "SELL", 4, newKey()))).andReturn())
                .path("ordNo").asText();

        mockMvc.perform(me("orders", t).param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].ordNo").value(third))
                .andExpect(jsonPath("$.content[0].regTs").value("2026-11-16T10:00:02+09:00"))
                .andExpect(jsonPath("$.content[0].tckCd").value(SAMSUNG))
                .andExpect(jsonPath("$.content[0].tckNm").value("삼성전자"))
                .andExpect(jsonPath("$.content[0].ordDvCd").value("SELL"))
                .andExpect(jsonPath("$.content[0].ordQty").value(4))
                .andExpect(jsonPath("$.content[0].ordPrc").value(70_000))
                .andExpect(jsonPath("$.content[0].trdAmt").value(280_000))
                .andExpect(jsonPath("$.content[0].ordStatCd").value("FILLED"))
                .andExpect(jsonPath("$.content[0].rjctRsnCd").isEmpty())
                .andExpect(jsonPath("$.content[1].ordNo").value(second))
                .andExpect(jsonPath("$.content[1].ordStatCd").value("REJECTED"))
                .andExpect(jsonPath("$.content[1].rjctRsnCd").value("INSUFFICIENT_HOLDING"))
                .andExpect(jsonPath("$.content[1].rjctRsnMsg").value("보유 수량이 부족합니다."))
                .andExpect(jsonPath("$.content[1].ordPrc").value(70_000))
                .andExpect(jsonPath("$.content[1].trdAmt").isEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(me("orders", t).param("page", "1").param("size", "2"))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].ordNo").value(first));

        mockMvc.perform(me("orders", t).param("ordStatCd", "REJECTED"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].ordNo").value(second));
        mockMvc.perform(me("orders", t).param("ordStatCd", "FILLED"))
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(me("orders", t).param("ordStatCd", "PENDING")).andExpect(status().isBadRequest());
        mockMvc.perform(me("orders", t).param("size", "101")).andExpect(status().isBadRequest());
        mockMvc.perform(me("orders", t).param("page", "-1")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("계좌 조회는 본인 것만 보이고, 관리자는 403이다(직무 분리)")
    void onlyOwnAccountAndNoAdmin() throws Exception {
        Trader owner = newTraderAt(MARKET_OPEN);
        putPriceNow(SAMSUNG, 70_000);
        mockMvc.perform(orderRequest(owner, orderBody(SAMSUNG, "BUY", 10, newKey()))).andExpect(status().isCreated());

        Trader other = newTraderAt(MARKET_OPEN);
        mockMvc.perform(me("holdings", other)).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(me("orders", other)).andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(me("summary", other)).andExpect(jsonPath("$.acntNo").value(other.acntNo()));

        Tokens admin = loginAsAdmin(createAdmin());
        for (String path : new String[] {"summary", "holdings", "orders", "withdrawals"}) {
            mockMvc.perform(get("/api/v1/accounts/me/" + path).header("Authorization", bearer(admin)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
    }
}
