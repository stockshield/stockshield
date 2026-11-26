package com.stockshield.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockshield.common.error.ApiException;
import com.stockshield.common.error.ErrorCode;
import com.stockshield.market.kis.KisQuoteClient;
import com.stockshield.order.MarketHours;
import com.stockshield.support.IntegrationTestSupport;
import com.sun.net.httpserver.HttpServer;

/**
 * 호가 API (차트·호가창 합의 5절). 가짜 KIS 서버로 필드 변환·캐시·잠금·실패 처리를 본다.
 * 실제 Redis·PostgreSQL(Testcontainers)을 쓴다.
 */
class OrderbookServiceTest extends IntegrationTestSupport {

    private static final String SAMSUNG = "005930";

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private HttpServer kis;
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final AtomicInteger bookCalls = new AtomicInteger();
    private final AtomicBoolean failing = new AtomicBoolean();

    @BeforeEach
    void startFakeKis() throws Exception {
        Set<String> keys = redis.keys("orderbook:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        redis.delete(List.of("kis:token", "kis:token:lock"));
        kis = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        kis.createContext("/oauth2/tokenP", ex -> {
            tokenCalls.incrementAndGet();
            reply(ex, 200, "{\"access_token\":\"fake-token\",\"expires_in\":86400}");
        });
        kis.createContext("/uapi/domestic-stock/v1/quotations/inquire-asking-price-exp-ccn", ex -> {
            bookCalls.incrementAndGet();
            if (failing.get()) {
                reply(ex, 500, "{}");
                return;
            }
            assertThat(ex.getRequestHeaders().getFirst("tr_id")).isEqualTo("FHKST01010200");
            assertThat(ex.getRequestHeaders().getFirst("authorization")).isEqualTo("Bearer fake-token");
            StringBuilder o = new StringBuilder("{\"rt_cd\":\"0\",\"output1\":{\"aspr_acpt_hour\":\"103931\"");
            for (int i = 1; i <= 10; i++) {
                // 10단계 매도는 0원(상한가 등으로 비어 있는 단계)
                long ask = i == 10 ? 0 : 192_600 + 100L * i;
                o.append(",\"askp").append(i).append("\":\"").append(ask).append('"')
                        .append(",\"askp_rsqn").append(i).append("\":\"").append(1000 + i).append('"')
                        .append(",\"bidp").append(i).append("\":\"").append(192_700 - 100L * i).append('"')
                        .append(",\"bidp_rsqn").append(i).append("\":\"").append(2000 + i).append('"');
            }
            o.append(",\"total_askp_rsqn\":\"27099\",\"total_bidp_rsqn\":\"78083\"}}");
            reply(ex, 200, o.toString());
        });
        kis.start();
    }

    @AfterEach
    void stopFakeKis() {
        kis.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws java.io.IOException {
        ex.getRequestBody().readAllBytes();
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private OrderbookService service(boolean marketOpen, Duration openTtl) {
        KisQuoteClient client = new KisQuoteClient("http://127.0.0.1:" + kis.getAddress().getPort(), "test-key",
                "test-secret", Duration.ofSeconds(3), redis);
        testClock.set(LocalDateTime.of(2026, 11, 17, 10, 40));
        // DEMO_MODE(true)면 항상 장중, false + 일요일 시각이면 장 밖
        MarketHours hours = new MarketHours(marketOpen, "");
        if (!marketOpen) {
            testClock.set(LocalDateTime.of(2026, 11, 15, 10, 40));
        }
        return new OrderbookService(client, redis, jdbc, hours, testClock, objectMapper, openTtl, Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("KIS 호가를 확정 필드로 바꾼다: 1단계부터 10단계, 가격 0인 단계 제외, 총잔량, 호가 시각, 장 상태")
    @SuppressWarnings("unchecked")
    void mapsFields() {
        Map<String, Object> book = service(true, Duration.ofSeconds(2)).orderbook(SAMSUNG);

        assertThat(book).containsEntry("tckCd", SAMSUNG).containsEntry("aspTs", "2026-11-17T10:39:31+09:00")
                .containsEntry("mktOpenYn", "Y").containsEntry("totAskRsqn", 27099L).containsEntry("totBidRsqn", 78083L);
        List<Map<String, Object>> asks = (List<Map<String, Object>>) book.get("asks");
        List<Map<String, Object>> bids = (List<Map<String, Object>>) book.get("bids");
        assertThat(asks).hasSize(9);
        assertThat(asks.get(0)).containsEntry("lvl", 1).containsEntry("prc", 192_700L).containsEntry("rsqn", 1001L);
        assertThat(bids).hasSize(10);
        assertThat(bids.get(0)).containsEntry("lvl", 1).containsEntry("prc", 192_600L).containsEntry("rsqn", 2001L);
        assertThat(bids.get(9)).containsEntry("lvl", 10).containsEntry("prc", 191_700L);
    }

    @Test
    @DisplayName("같은 종목을 동시에 여러 번 요청해도 캐시 주기 안에서는 KIS 호가 호출 1번, 토큰 발급 1번")
    void oneKisCallPerCachePeriod() throws Exception {
        OrderbookService s = service(true, Duration.ofSeconds(2));
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Map<String, Object>>> calls = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                calls.add(() -> s.orderbook(SAMSUNG));
            }
            for (Future<Map<String, Object>> f : pool.invokeAll(calls)) {
                assertThat(f.get()).containsEntry("tckCd", SAMSUNG);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(bookCalls.get()).isEqualTo(1);
        assertThat(tokenCalls.get()).isEqualTo(1);

        // 캐시가 끝나면 다시 부르지만 토큰은 Redis에 있어 새로 받지 않는다
        redis.delete(List.of("orderbook:" + SAMSUNG, "orderbook:lock:" + SAMSUNG));
        s.orderbook(SAMSUNG);
        assertThat(bookCalls.get()).isEqualTo(2);
        assertThat(tokenCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("장 밖에는 mktOpenYn=N이고 캐시를 60초 둔다")
    void closedMarket() {
        Map<String, Object> book = service(false, Duration.ofSeconds(2)).orderbook(SAMSUNG);
        assertThat(book).containsEntry("mktOpenYn", "N");
        Long ttl = redis.getExpire("orderbook:" + SAMSUNG);
        assertThat(ttl).isBetween(50L, 60L);
    }

    @Test
    @DisplayName("KIS 실패: 마지막 호가가 없으면 503 ORDERBOOK_UNAVAILABLE, 있으면 마지막 호가를 보여 준다")
    void kisFailure() {
        OrderbookService s = service(true, Duration.ofSeconds(2));
        failing.set(true);
        assertThatThrownBy(() -> s.orderbook(SAMSUNG))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).errorCode()).isEqualTo(ErrorCode.ORDERBOOK_UNAVAILABLE));

        failing.set(false);
        redis.delete("orderbook:lock:" + SAMSUNG);
        s.orderbook(SAMSUNG);                                     // 정상으로 한 번 받아 둠
        redis.delete(List.of("orderbook:" + SAMSUNG, "orderbook:lock:" + SAMSUNG));
        failing.set(true);
        assertThat(s.orderbook(SAMSUNG)).containsEntry("aspTs", "2026-11-17T10:39:31+09:00");
    }

    @Test
    @DisplayName("서비스 대상 30종목 밖이면 404 STOCK_NOT_FOUND (KIS를 부르지 않음)")
    void unknownStock() throws Exception {
        mockMvc.perform(get("/api/v1/market/stocks/999999/orderbook"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STOCK_NOT_FOUND"));
        assertThat(bookCalls.get()).isZero();
    }

    @Test
    @DisplayName("호가 시각: 지금보다 늦은 시각이면 전날 호가로 본다(장 전·휴장일)")
    void aspTsDate() {
        LocalDateTime morning = LocalDateTime.of(2026, 11, 18, 8, 0);
        assertThat(OrderbookService.aspTs("153000", morning)).isEqualTo("2026-11-17T15:30:00+09:00");
        assertThat(OrderbookService.aspTs("075950", morning)).isEqualTo("2026-11-18T07:59:50+09:00");
        assertThat(OrderbookService.aspTs("", morning)).isNull();
    }
}
