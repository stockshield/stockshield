package com.stockshield.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.messaging.simp.SimpMessageSendingOperations;

import com.stockshield.fds.risk.StockRiskJudge;
import com.stockshield.order.MarketHours;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.stockshield.support.IntegrationTestSupport;

/**
 * 시세·종목 조회 API를 MockMvc로 확인한다 (API 명세서 'API_명세서' 10~13행, FDS_MKT_001 #4).
 * 이 클래스 전용 Redis 컨테이너를 쓰고 테스트마다 비운다. 종목은 다른 테스트와 겹치지 않는 코드(Q로 시작)를 쓴다.
 */
@AutoConfigureMockMvc
class MarketControllerTest extends IntegrationTestSupport {

    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.2.17"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        REDIS.start();
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired MockMvc mvc;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MarketQueryService queryService;
    @Autowired StockRiskJudge riskJudge;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        for (int i = 1; i <= 12; i++) {
            stock(String.format("Q%05d", i), "큐종목" + i);
        }
        stock("Q99999", "검색_특수%종목");
    }

    @Test
    @DisplayName("검색: 종목코드 앞부분·종목명 일부로 찾고, 빈 검색어는 빈 목록, LIKE 특수문자는 글자 그대로 찾는다")
    void search() throws Exception {
        mvc.perform(get("/api/v1/market/stocks/search").param("keyword", "Q0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].tckCd", contains("Q00010", "Q00011", "Q00012")));
        mvc.perform(get("/api/v1/market/stocks/search").param("keyword", "큐종목1"))
                .andExpect(jsonPath("$[0].tckCd").value("Q00001"));
        mvc.perform(get("/api/v1/market/stocks/search").param("keyword", "  "))
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/market/stocks/search").param("keyword", "_특수%"))
                .andExpect(jsonPath("$[*].tckCd", contains("Q99999")));
    }

    @Test
    @DisplayName("메인 요약: 상승률·하락률·거래량 상위 10, 지수, 위험 의심 종목(riskYn), 시세 지연 아님")
    void summary() throws Exception {
        for (int i = 1; i <= 12; i++) {
            price(String.format("Q%05d", i), 10_000 + i, i - 6, i * 1000L, now());
        }
        redis.opsForValue().set("index:0001", "{\"idxCd\":\"0001\",\"currentVal\":2650.31,\"chgRt\":0.42,\"prcTs\":\"" + now() + "\"}");
        risk("Q00012", "UP", "6", "전일 대비 +10.50%");

        mvc.perform(get("/api/v1/market/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceDelayed").value(false))
                .andExpect(jsonPath("$.kospi.currentVal").value(2650.31))
                .andExpect(jsonPath("$.kosdaq").value(nullValue()))
                .andExpect(jsonPath("$.topRanks.rise", hasSize(10)))
                .andExpect(jsonPath("$.topRanks.rise[0].tckCd").value("Q00012"))
                .andExpect(jsonPath("$.topRanks.rise[0].tckNm").value("큐종목12"))
                .andExpect(jsonPath("$.topRanks.rise[0].riskYn").value("Y"))
                .andExpect(jsonPath("$.topRanks.fall[0].tckCd").value("Q00001"))
                .andExpect(jsonPath("$.topRanks.volume[0].tckCd").value("Q00012"))
                .andExpect(jsonPath("$.riskStocks[0].tckCd").value("Q00012"))
                .andExpect(jsonPath("$.riskStocks[0].dir").value("UP"));
    }

    @Test
    @DisplayName("시세가 60초 넘게 끊기면 priceDelayed = true이고 위험 의심 목록은 비운다")
    void delayedHidesRisk() throws Exception {
        price("Q00001", 10_000, 12, 1000, OffsetDateTime.now(ZoneOffset.ofHours(9)).minusSeconds(61)
                .truncatedTo(ChronoUnit.SECONDS).toString());
        risk("Q00001", "UP", "12", "전일 대비 +12.00%");
        mvc.perform(get("/api/v1/market/summary"))
                .andExpect(jsonPath("$.priceDelayed").value(true))
                .andExpect(jsonPath("$.riskStocks", hasSize(0)))
                // 순위의 riskYn도 riskStocks와 같게 N (회신 8번 답 2)
                .andExpect(jsonPath("$.topRanks.rise[0].tckCd").value("Q00001"))
                .andExpect(jsonPath("$.topRanks.rise[0].riskYn").value("N"));
        mvc.perform(get("/api/v1/market/risk-stocks")).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/market/stocks/Q00001"))
                .andExpect(jsonPath("$.risk.riskYn").value("N"))
                .andExpect(jsonPath("$.priceDelayed").value(true));
    }

    @Test
    @DisplayName("종목 상세의 priceDelayed는 그 종목의 시세 기준: 다른 종목이 새로워도 이 종목이 60초 넘게 끊기면 true, 정확히 60초는 false")
    void detailDelayedPerStock() throws Exception {
        price("Q00001", 10_000, 1, 1000, now());
        price("Q00002", 20_000, 1, 1000, ago(61));
        price("Q00003", 30_000, 1, 1000, ago(59));
        mvc.perform(get("/api/v1/market/stocks/Q00001")).andExpect(jsonPath("$.priceDelayed").value(false));
        mvc.perform(get("/api/v1/market/stocks/Q00002")).andExpect(jsonPath("$.priceDelayed").value(true));
        mvc.perform(get("/api/v1/market/stocks/Q00003")).andExpect(jsonPath("$.priceDelayed").value(false));
        mvc.perform(get("/api/v1/market/stocks/Q00004")).andExpect(jsonPath("$.priceDelayed").value(true));
    }

    @Test
    @DisplayName("관제 시세 지연 경고: 종목별로 지연 시작·해제 때만 보내고, 장 운영 시간 밖에서는 보내지 않는다")
    void adminMarketDelayPerStock() {
        SimpMessageSendingOperations ops = mock(SimpMessageSendingOperations.class);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ops", ops);
        MarketBroadcaster open = new MarketBroadcaster(beans.getBeanProvider(SimpMessageSendingOperations.class),
                riskJudge, queryService, new MarketHours(true, ""), Clock.systemDefaultZone());

        price("Q00001", 10_000, 1, 1000, now());
        price("Q00002", 20_000, 1, 1000, ago(120));
        open.checkDelay();
        assertThat(adminMessagesFor(ops, "Q00001")).isEmpty();
        assertThat(adminMessagesFor(ops, "Q00002")).singleElement().satisfies(m -> {
            assertThat(m.get("tckNm")).isEqualTo("큐종목2");
            assertThat(m.get("priceDelayed")).isEqualTo(true);
            assertThat(m.get("prcTs")).isNotNull();
        });

        clearInvocations(ops);
        open.checkDelay();   // 바뀐 것이 없으면 보내지 않는다
        assertThat(adminMessagesFor(ops, "Q00002")).isEmpty();

        price("Q00002", 20_100, 1, 1000, now());
        open.checkDelay();
        assertThat(adminMessagesFor(ops, "Q00002")).singleElement()
                .satisfies(m -> assertThat(m.get("priceDelayed")).isEqualTo(false));

        // 일요일 낮(장 운영 시간 밖)에는 판단하지 않는다
        SimpMessageSendingOperations closedOps = mock(SimpMessageSendingOperations.class);
        StaticListableBeanFactory closedBeans = new StaticListableBeanFactory();
        closedBeans.addBean("ops", closedOps);
        Clock sunday = Clock.fixed(LocalDateTime.of(2026, 11, 15, 11, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                ZoneId.of("Asia/Seoul"));
        MarketBroadcaster closed = new MarketBroadcaster(closedBeans.getBeanProvider(SimpMessageSendingOperations.class),
                riskJudge, queryService, new MarketHours(false, ""), sunday);
        price("Q00003", 30_000, 1, 1000, ago(600));
        closed.checkDelay();
        verify(closedOps, never()).convertAndSend(eq(MarketBroadcaster.ADMIN_MARKET_TOPIC), any(Object.class));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> adminMessagesFor(SimpMessageSendingOperations ops, String code) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ops, atLeast(0)).convertAndSend(eq(MarketBroadcaster.ADMIN_MARKET_TOPIC), captor.capture());
        return captor.getAllValues().stream().map(o -> (Map<String, Object>) o)
                .filter(m -> code.equals(m.get("tckCd"))).toList();
    }

    @Test
    @DisplayName("종목 상세: 현재가, 가장 최근 거래일의 1분봉만 시간순, 위험 의심이면 riskYn=Y와 방향·사유")
    void detail() throws Exception {
        price("Q00002", 20_500, 3, 5000, now());
        risk("Q00002", "DOWN", "-3", "1분 수익률 -2.10% (Z=-3.4)");
        bar("Q00002", "202611120930", 19_000);   // 전 거래일
        bar("Q00002", "202611130901", 20_000);
        bar("Q00002", "202611130900", 20_100);

        mvc.perform(get("/api/v1/market/stocks/Q00002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tckNm").value("큐종목2"))
                .andExpect(jsonPath("$.currentPrice").value(20_500))
                .andExpect(jsonPath("$.mktCd").value("KOSPI"))
                .andExpect(jsonPath("$.totalTrdVol").value(5000))
                .andExpect(jsonPath("$.priceDelayed").value(false))
                .andExpect(jsonPath("$.chart[*].bsMi", contains("202611130900", "202611130901")))
                .andExpect(jsonPath("$.chart[0].closePrc").value(20_100))
                .andExpect(jsonPath("$.risk.riskYn").value("Y"))
                .andExpect(jsonPath("$.risk.dir").value("DOWN"))
                .andExpect(jsonPath("$.risk.reason").value("1분 수익률 -2.10% (Z=-3.4)"));
        mvc.perform(get("/api/v1/market/risk-stocks"))
                .andExpect(jsonPath("$[0].tckNm").value("큐종목2"));
    }

    @Test
    @DisplayName("서비스 대상이 아닌 종목은 404 STOCK_NOT_FOUND (공통 에러 형식)")
    void unknownStock() throws Exception {
        mvc.perform(get("/api/v1/market/stocks/NOPE01"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STOCK_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    // ------------------------------------------------------------------ 도우미

    private static String now() {
        return OffsetDateTime.now(ZoneOffset.ofHours(9)).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static String ago(int seconds) {
        return OffsetDateTime.now(ZoneOffset.ofHours(9)).minusSeconds(seconds).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private void stock(String code, String name) {
        jdbcTemplate.update("INSERT INTO stock_master (tck_cd, tck_nm, mkt_cd) VALUES (?, ?, 'KOSPI') "
                + "ON CONFLICT (tck_cd) DO NOTHING", code, name);
    }

    private void price(String code, long price, double chgRt, long accVol, String prcTs) {
        redis.opsForValue().set("price:" + code, String.format(java.util.Locale.ROOT,
                "{\"tckCd\":\"%s\",\"currentPrice\":%d,\"prcTs\":\"%s\",\"chgPrc\":0,\"chgRt\":%.2f,\"totalTrdVol\":%d}",
                code, price, prcTs, chgRt, accVol));
    }

    private void risk(String code, String dir, String chgRt, String reason) {
        redis.opsForValue().set("risk:stock:" + code, String.format(
                "{\"tckCd\":\"%s\",\"riskYn\":\"Y\",\"dir\":\"%s\",\"chgRt\":%s,\"reason\":\"%s\",\"dtcTs\":\"%s\"}",
                code, dir, chgRt, reason, now()));
    }

    private void bar(String code, String bsMi, long close) {
        jdbcTemplate.update("INSERT INTO price_minute (tck_cd, bs_mi, open_prc, high_prc, low_prc, close_prc, trd_vol) "
                + "VALUES (?, ?, ?, ?, ?, ?, 10) ON CONFLICT DO NOTHING", code, bsMi, close, close, close, close);
    }
}
