package com.stockshield.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockshield.market.CandleService.Candle;
import com.stockshield.market.kis.KisQuoteClient;
import com.stockshield.support.IntegrationTestSupport;
import com.sun.net.httpserver.HttpServer;

/**
 * 일·주·월봉 (차트·호가창 합의 3·4절). 실제 PostgreSQL·Redis와 가짜 KIS 기간별 시세로 본다.
 */
class CandleTest extends IntegrationTestSupport {

    private static final String SAMSUNG = "005930";
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ObjectMapper objectMapper;

    private HttpServer kis;
    private final AtomicInteger dailyCalls = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        // 앱 계정에는 DELETE 권한이 없어(V6) 정리는 관리자 계정으로
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement st = c.createStatement()) {
            st.execute("DELETE FROM price_daily");
            st.execute("DELETE FROM price_minute WHERE tck_cd = '005930'");
        }
        redis.delete(List.of("price:" + SAMSUNG, "kis:token", "kis:token:lock"));
        kis = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        kis.createContext("/oauth2/tokenP", ex -> reply(ex, "{\"access_token\":\"t\",\"expires_in\":86400}"));
        // 요청 구간의 평일마다 일봉 하나(가격은 날짜로 정함), 최신부터 최대 100건
        kis.createContext("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice", ex -> {
            dailyCalls.incrementAndGet();
            Map<String, String> q = query(ex.getRequestURI());
            assertThat(q).containsEntry("FID_ORG_ADJ_PRC", "0").containsEntry("FID_PERIOD_DIV_CODE", "D");
            LocalDate from = LocalDate.parse(q.get("FID_INPUT_DATE_1"), YMD);
            LocalDate to = LocalDate.parse(q.get("FID_INPUT_DATE_2"), YMD);
            List<String> rows = new ArrayList<>();
            for (LocalDate d = to; !d.isBefore(from) && rows.size() < 100; d = d.minusDays(1)) {
                if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                    continue;
                }
                long p = 50_000 + d.getDayOfYear();
                rows.add("{\"stck_bsop_date\":\"" + d.format(YMD) + "\",\"stck_oprc\":\"" + p + "\",\"stck_hgpr\":\""
                        + (p + 500) + "\",\"stck_lwpr\":\"" + (p - 500) + "\",\"stck_clpr\":\"" + (p + 100)
                        + "\",\"acml_vol\":\"1000\"}");
            }
            reply(ex, "{\"rt_cd\":\"0\",\"output2\":[" + String.join(",", rows) + "]}");
        });
        kis.start();
    }

    @AfterEach
    void tearDown() {
        kis.stop(0);
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> m = new HashMap<>();
        for (String p : uri.getRawQuery().split("&")) {
            String[] kv = p.split("=", 2);
            m.put(kv[0], kv.length > 1 ? kv[1] : "");
        }
        return m;
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, String body) throws java.io.IOException {
        ex.getRequestBody().readAllBytes();
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private DailyCandleLoader loader() {
        KisQuoteClient client = new KisQuoteClient("http://127.0.0.1:" + kis.getAddress().getPort(), "k", "s",
                Duration.ofSeconds(3), redis);
        return new DailyCandleLoader(client, jdbc, testClock, false, 0);
    }

    private CandleService service() {
        return new CandleService(jdbc, redis, objectMapper, testClock);
    }

    private void daily(String bsDt, long o, long h, long l, long c, long v) {
        jdbc.update("INSERT INTO price_daily (tck_cd, bs_dt, open_prc, high_prc, low_prc, close_prc, trd_vol) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)", SAMSUNG, bsDt, o, h, l, c, v);
    }

    private void minute(String bsMi, long o, long h, long l, long c, long v) {
        jdbc.update("INSERT INTO price_minute (tck_cd, bs_mi, open_prc, high_prc, low_prc, close_prc, trd_vol) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)", SAMSUNG, bsMi, o, h, l, c, v);
    }

    private static long weekdaysBetween(LocalDate from, LocalDate toExclusive) {
        long n = 0;
        for (LocalDate d = from; d.isBefore(toExclusive); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ 적재

    @Test
    @DisplayName("처음 적재: 3년 치를 130일 구간으로 나눠 받고, 장중이면 오늘 행은 저장하지 않으며 빠진 날이 없다")
    void initialLoad() {
        testClock.set(LocalDateTime.of(2026, 11, 17, 11, 0));
        int[] r = loader().fill(SAMSUNG);

        LocalDate today = LocalDate.of(2026, 11, 17);
        long expected = weekdaysBetween(today.minusYears(3), today);
        assertThat(r[0]).isEqualTo(dailyCalls.get()).isBetween(8, 10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM price_daily WHERE tck_cd = ?", Long.class, SAMSUNG))
                .isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT MAX(bs_dt) FROM price_daily WHERE tck_cd = ?", String.class, SAMSUNG))
                .isEqualTo("20261116");
    }

    @Test
    @DisplayName("이어서 적재: 마지막 저장일부터 다시 받아 서버가 꺼졌던 날을 채우고, 15:40 뒤면 오늘 공식 일봉도 저장")
    void fillsGapAndToday() {
        daily("20261112", 1, 1, 1, 1, 1);           // 11/12까지만 있고 13·16·17이 빠짐
        testClock.set(LocalDateTime.of(2026, 11, 17, 16, 10));
        int[] r = loader().fill(SAMSUNG);

        assertThat(r[0]).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT bs_dt FROM price_daily WHERE tck_cd = ? ORDER BY bs_dt", String.class, SAMSUNG))
                .containsExactly("20261112", "20261113", "20261116", "20261117");
        // 마지막 저장일(11/12)도 공식 값으로 덮어씀
        assertThat(jdbc.queryForObject("SELECT close_prc FROM price_daily WHERE tck_cd = ? AND bs_dt = '20261112'",
                Long.class, SAMSUNG)).isEqualTo(50_000L + LocalDate.of(2026, 11, 12).getDayOfYear() + 100);
    }

    // ------------------------------------------------------------------ 조회

    private void seedWeekAndMonth() {
        daily("20261031", 100, 120, 90, 110, 10);   // 10월(금)
        daily("20261103", 110, 130, 100, 120, 10);  // 11/3(화) — 11월 첫 거래일
        daily("20261113", 120, 140, 110, 130, 10);  // 11/13(금)
        daily("20261116", 130, 150, 120, 140, 10);  // 11/16(월)
        daily("20261117", 140, 160, 130, 150, 10);  // 11/17(화)
    }

    @Test
    @DisplayName("일봉: 오래된 것부터, 마지막은 오늘 1분봉을 묶은 진행 중인 봉이고 종가는 Redis 현재 시세")
    void dailyWithLiveBar() throws Exception {
        seedWeekAndMonth();
        testClock.set(LocalDateTime.of(2026, 11, 18, 10, 30));
        minute("202611180900", 151, 155, 149, 152, 5);
        minute("202611180901", 152, 158, 150, 157, 7);
        minute("202611180902", 157, 157, 153, 154, 3);
        redis.opsForValue().set("price:" + SAMSUNG, objectMapper.writeValueAsString(
                Map.of("tckCd", SAMSUNG, "currentPrice", 160, "prcTs", "2026-11-18T10:30:00+09:00")));

        @SuppressWarnings("unchecked")
        List<Candle> d = (List<Candle>) service().candles(SAMSUNG, "D").get("candles");
        assertThat(d).extracting(Candle::bsDt)
                .containsExactly("20261031", "20261103", "20261113", "20261116", "20261117", "20261118");
        // 시가 = 첫 1분봉 시가, 고가 = max(158, 현재가 160), 저가 = 149, 종가 = 현재가, 거래량 = 합
        assertThat(d.get(5)).isEqualTo(new Candle("20261118", 151, 160, 149, 160, 15));
    }

    @Test
    @DisplayName("주봉은 월~금, 월봉은 달력 기준으로 묶고 bsDt는 첫 거래일. 진행 중인 봉도 이번 주·이번 달에 들어간다")
    void weekAndMonth() throws Exception {
        seedWeekAndMonth();
        testClock.set(LocalDateTime.of(2026, 11, 18, 10, 30));
        minute("202611180900", 151, 155, 149, 152, 5);

        @SuppressWarnings("unchecked")
        List<Candle> w = (List<Candle>) service().candles(SAMSUNG, "W").get("candles");
        assertThat(w).containsExactly(
                new Candle("20261031", 100, 120, 90, 110, 10),
                new Candle("20261103", 110, 130, 100, 120, 10),
                new Candle("20261113", 120, 140, 110, 130, 10),
                new Candle("20261116", 130, 160, 120, 152, 25));

        @SuppressWarnings("unchecked")
        List<Candle> m = (List<Candle>) service().candles(SAMSUNG, "M").get("candles");
        assertThat(m).containsExactly(
                new Candle("20261031", 100, 120, 90, 110, 10),
                new Candle("20261103", 110, 160, 100, 152, 45));
    }

    @Test
    @DisplayName("장 전·휴장일(오늘 1분봉 없음)은 직전 거래일까지, 오늘 공식 일봉이 있으면 진행 중인 봉을 붙이지 않는다")
    void noLiveBar() {
        seedWeekAndMonth();
        testClock.set(LocalDateTime.of(2026, 11, 18, 8, 30));
        @SuppressWarnings("unchecked")
        List<Candle> before = (List<Candle>) service().candles(SAMSUNG, "D").get("candles");
        assertThat(before.get(before.size() - 1).bsDt()).isEqualTo("20261117");

        testClock.set(LocalDateTime.of(2026, 11, 17, 16, 20));
        minute("202611170900", 1, 1, 1, 1, 1);
        @SuppressWarnings("unchecked")
        List<Candle> after = (List<Candle>) service().candles(SAMSUNG, "D").get("candles");
        assertThat(after).hasSize(5);
        assertThat(after.get(4)).isEqualTo(new Candle("20261117", 140, 160, 130, 150, 10));
    }

    @Test
    @DisplayName("계약: 응답 형식, interval이 D·W·M이 아니면 400 INVALID_REQUEST, 30종목 밖이면 404")
    void contract() throws Exception {
        daily("20261117", 140, 160, 130, 150, 10);
        mockMvc.perform(get("/api/v1/market/stocks/005930/candles").param("interval", "D"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tckCd").value(SAMSUNG))
                .andExpect(jsonPath("$.interval").value("D"))
                .andExpect(jsonPath("$.candles[0].bsDt").value("20261117"))
                .andExpect(jsonPath("$.candles[0].openPrc").value(140))
                .andExpect(jsonPath("$.candles[0].trdVol").value(10));
        mockMvc.perform(get("/api/v1/market/stocks/005930/candles").param("interval", "1일"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/v1/market/stocks/005930/candles"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/market/stocks/999999/candles").param("interval", "D"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STOCK_NOT_FOUND"));
    }
}
