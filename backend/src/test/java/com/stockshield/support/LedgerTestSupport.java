package com.stockshield.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.UnsupportedEncodingException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.stockshield.common.config.ClockConfig;

/**
 * 원장(주문·출금) 통합 테스트의 공통 도우미. 설정을 더하지 않아 IntegrationTestSupport와 같은 컨텍스트를 씀.
 * 실제 시각은 주말·장 마감 뒤일 수 있어, 주문 테스트는 고정 Clock을 장중(MARKET_OPEN)으로 맞춘 뒤 로그인함
 * (토큰 만료도 같은 Clock으로 판단하므로 시각을 바꾸면 다시 로그인).
 */
public abstract class LedgerTestSupport extends IntegrationTestSupport {

    /** 2026-11-16(월) 10:00, 휴장일 아님 */
    protected static final LocalDateTime MARKET_OPEN = LocalDateTime.of(2026, 11, 16, 10, 0);
    protected static final String SAMSUNG = "005930";
    protected static final String HYNIX = "000660";
    protected static final long PKG_10M = 10_000_000L;

    @Autowired
    protected JdbcTemplate db;

    /** 가입부터 로그인까지 한 번에. 시각은 호출 전에 testClock으로 맞춰 둠 */
    protected record Trader(TestMember member, Tokens tokens) {
        public String acntNo() {
            return member.acntNo();
        }
    }

    protected Trader newTraderAt(LocalDateTime now) throws Exception {
        testClock.set(now);
        TestMember m = signUp();
        return new Trader(m, login(m));
    }

    /** 시각을 옮기고 그 시각의 토큰으로 다시 로그인 */
    protected Trader moveTo(Trader t, LocalDateTime now) throws Exception {
        testClock.set(now);
        return new Trader(t.member(), login(t.member()));
    }

    /** 시세 시각이 지금(고정 Clock)인 시세 */
    protected void putPriceNow(String tckCd, long currentPrice) throws Exception {
        putPrice(tckCd, currentPrice, seoul(testClock.now()));
    }

    protected static OffsetDateTime seoul(LocalDateTime t) {
        return t.atZone(ClockConfig.ZONE).toOffsetDateTime();
    }

    /** 람다 안에서 쓰려고 검사 예외를 감쌈 */
    protected static String content(MvcResult r) {
        try {
            return r.getResponse().getContentAsString();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }

    protected static Map<String, Object> orderBody(String tckCd, String ordDvCd, long ordQty, String idmpKey) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("tckCd", tckCd);
        b.put("ordDvCd", ordDvCd);
        b.put("ordQty", ordQty);
        b.put("idmpKey", idmpKey);
        return b;
    }

    protected MockHttpServletRequestBuilder orderRequest(Trader t, Map<String, Object> body) throws Exception {
        return post("/api/v1/orders").with(fromIp(t.member().ip()))
                .header("Authorization", bearer(t.tokens()))
                .header("X-Device-Id", t.member().deviceId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body));
    }

    protected MvcResult order(Trader t, String tckCd, String ordDvCd, long ordQty) throws Exception {
        return mockMvc.perform(orderRequest(t, orderBody(tckCd, ordDvCd, ordQty, newKey()))).andReturn();
    }

    // ------------------------------------------------------------------ 원장 조회

    protected long dpsBal(String acntNo) {
        return db.queryForObject("SELECT dps_bal FROM account WHERE acnt_no = ?", Long.class, acntNo);
    }

    protected long hldQty(String acntNo, String tckCd) {
        return db.queryForObject(
                "SELECT COALESCE((SELECT hld_qty FROM holding WHERE acnt_no = ? AND tck_cd = ?), 0)",
                Long.class, acntNo, tckCd);
    }

    protected int orderCount(String acntNo) {
        return db.queryForObject("SELECT COUNT(*) FROM orders WHERE acnt_no = ?", Integer.class, acntNo);
    }

    protected int tradeCount(String acntNo) {
        return db.queryForObject("SELECT COUNT(*) FROM trade_history WHERE acnt_no = ?", Integer.class, acntNo);
    }

    /** 원장 정합: 예수금 = 초기 예수금 − 매수 체결금액 + 매도 체결금액 − DONE 출금 (ADM_REC_001 #1) */
    protected long expectedDpsBal(String acntNo) {
        return db.queryForObject("""
                SELECT a.init_dps_amt
                     - COALESCE((SELECT SUM(trd_amt) FROM trade_history WHERE acnt_no = a.acnt_no AND trd_dv_cd = 'BUY'), 0)
                     + COALESCE((SELECT SUM(trd_amt) FROM trade_history WHERE acnt_no = a.acnt_no AND trd_dv_cd = 'SELL'), 0)
                     - COALESCE((SELECT SUM(wdr_amt) FROM withdrawal WHERE acnt_no = a.acnt_no AND wdr_stat_cd = 'DONE'), 0)
                  FROM account a WHERE a.acnt_no = ?
                """, Long.class, acntNo);
    }

    /** 원장 정합: 보유 수량 = 매수 수량 − 매도 수량 */
    protected long expectedHldQty(String acntNo, String tckCd) {
        return db.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN trd_dv_cd = 'BUY' THEN trd_qty ELSE -trd_qty END), 0)
                  FROM trade_history WHERE acnt_no = ? AND tck_cd = ?
                """, Long.class, acntNo, tckCd);
    }

    // ------------------------------------------------------------------ 동시 요청

    /** 요청들을 스레드마다 하나씩 준비시킨 뒤 한꺼번에 출발시킴. 결과는 넣은 순서 */
    protected List<MvcResult> concurrently(List<Callable<MvcResult>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (Callable<MvcResult> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return call.call();
                }));
            }
            ready.await();
            start.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
