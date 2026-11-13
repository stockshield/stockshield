package com.stockshield.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockshield.support.IntegrationTestSupport;

/**
 * 시세 컨슈머가 Redis 최신 시세와 분봉을 쓰는지 실제 Redis 7.2.17·PostgreSQL에서 확인한다.
 * MKT_TICK_002 #1·#3, 연결 합의서 9절(합의 18: price:{tckCd}의 currentPrice·prcTs, StringRedisTemplate 직렬화)
 * Kafka는 띄우지 않고 메시지 처리 메서드를 직접 부른다(리스너는 이 테스트에서 끈다).
 */
class MarketTickListenerTest extends IntegrationTestSupport {

    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.2.17"))
            .withExposedPorts(6379);

    @BeforeAll
    static void startRedis() {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        REDIS.start();
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired MarketTickListener listener;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    @Test
    @DisplayName("체결 메시지는 price:{tckCd}에 currentPrice(정수)와 메시지의 시세 시각을 JSON 문자열로 쓴다")
    void writesPriceCache() throws Exception {
        listener.onMessage("""
                {"v":1,"type":"TICK","code":"005930","currentPrice":214500,"chgPrc":-1500,"chgRt":-0.69,
                 "trdVol":12,"accVol":1203344,"prcTs":"2026-11-13T12:30:01+09:00","src":"KIS","extra":"무시"}
                """);
        String raw = redis.opsForValue().get("price:005930");
        JsonNode v = objectMapper.readTree(raw);
        assertThat(v.get("tckCd").asText()).isEqualTo("005930");
        assertThat(v.get("currentPrice").isIntegralNumber()).isTrue();
        assertThat(v.get("currentPrice").asLong()).isEqualTo(214_500L);
        assertThat(v.get("prcTs").asText()).isEqualTo("2026-11-13T12:30:01+09:00");
        assertThat(raw).doesNotContain("@class");
    }

    @Test
    @DisplayName("같은 분의 체결은 한 봉으로 모은다: 시가는 처음, 고가·저가는 최대·최소, 종가는 마지막, 거래량은 합")
    void aggregatesMinuteBar() {
        stock("000660");
        tick("000660", 1_240_000, 10, "2026-11-13T12:31:05+09:00");
        tick("000660", 1_250_000, 5, "2026-11-13T12:31:20+09:00");
        tick("000660", 1_235_000, 7, "2026-11-13T12:31:40+09:00");
        tick("000660", 1_245_000, 3, "2026-11-13T12:31:59+09:00");
        tick("000660", 1_246_000, 1, "2026-11-13T12:32:00+09:00");   // 다음 분

        Map<String, Object> bar = jdbcTemplate.queryForMap(
                "SELECT * FROM price_minute WHERE tck_cd = '000660' AND bs_mi = '202611131231'");
        assertThat(((Number) bar.get("open_prc")).longValue()).isEqualTo(1_240_000L);
        assertThat(((Number) bar.get("high_prc")).longValue()).isEqualTo(1_250_000L);
        assertThat(((Number) bar.get("low_prc")).longValue()).isEqualTo(1_235_000L);
        assertThat(((Number) bar.get("close_prc")).longValue()).isEqualTo(1_245_000L);
        assertThat(((Number) bar.get("trd_vol")).longValue()).isEqualTo(25L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM price_minute WHERE tck_cd = '000660' AND bs_mi = '202611131232'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("stock_master에 없는 종목도 최신 시세는 쓰고, 분봉은 건너뛴다")
    void unknownStockStillCachesPrice() {
        tick("999999", 1_000, 1, "2026-11-13T12:33:00+09:00");
        assertThat(redis.opsForValue().get("price:999999")).contains("\"currentPrice\":1000");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM price_minute WHERE tck_cd = '999999'", Integer.class)).isZero();
    }

    @Test
    @DisplayName("지수 메시지는 index:{idxCd}에 소수 값을 쓴다")
    void writesIndex() {
        listener.handle(new MarketTick(1, "INDEX", "0001", null, new BigDecimal("2650.31"), null,
                new BigDecimal("0.42"), null, null, "2026-11-13T12:34:00+09:00", null, "KIS"));
        assertThat(redis.opsForValue().get("index:0001")).contains("\"idxVal\":2650.31");
    }

    @Test
    @DisplayName("해석할 수 없는 메시지는 예외 없이 건너뛴다")
    void ignoresBrokenMessage() {
        listener.onMessage("{not json");
        listener.onMessage("{\"v\":1,\"type\":\"TICK\"}");
    }

    private void tick(String code, long price, long vol, String ts) {
        listener.handle(new MarketTick(1, "TICK", code, price, null, 0L, BigDecimal.ZERO, vol, null, ts, null, "KIS"));
    }

    private void stock(String code) {
        jdbcTemplate.update("INSERT INTO stock_master (tck_cd, tck_nm, mkt_cd) VALUES (?, '테스트종목', 'KOSPI') "
                + "ON CONFLICT (tck_cd) DO NOTHING", code);
    }
}
