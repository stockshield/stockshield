package com.stockshield.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.stockshield.support.LedgerTestSupport;

/**
 * Redis 장애 중 주문·출금 (SYS_CORE_002 #5, TRD_ORD_001 #2·#3, API 명세서 'API_명세서' 17행)과 Redis 호출 시간 제한(SYS_CORE_001 구현방안).
 * 공용 Redis는 멈추지 않고 이 클래스 전용 Redis를 docker pause로 멈춘다(RedisOutageLoginTest와 같은 방법).
 */
class OrderRedisOutageTest extends LedgerTestSupport {

    static final GenericContainer<?> OWN_REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.2.17")).withExposedPorts(6379);

    @DynamicPropertySource
    static void ownRedis(DynamicPropertyRegistry registry) {
        OWN_REDIS.start();
        registry.add("spring.data.redis.host", OWN_REDIS::getHost);
        registry.add("spring.data.redis.port", () -> OWN_REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired RedisConnectionFactory redisConnectionFactory;
    @Autowired StringRedisTemplate redis;

    private boolean paused;

    @AfterEach
    void resumeRedis() {
        if (paused) {
            DockerClientFactory.instance().client().unpauseContainerCmd(OWN_REDIS.getContainerId()).exec();
            paused = false;
        }
    }

    @Test
    @DisplayName("시세를 읽을 수 없으면 새 주문은 503 PRICE_UNAVAILABLE로 아무것도 저장하지 않고, 이미 처리된 키의 재요청은 처음 결과를 받는다")
    void newOrderRefusedButProcessedKeyReplays() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        putPriceNow(SAMSUNG, 70_000);
        Map<String, Object> processed = orderBody(SAMSUNG, "BUY", 10, newKey());
        MvcResult first = mockMvc.perform(orderRequest(t, processed)).andExpect(status().isCreated()).andReturn();

        DockerClientFactory.instance().client().pauseContainerCmd(OWN_REDIS.getContainerId()).exec();
        paused = true;

        Map<String, Object> fresh = orderBody(SAMSUNG, "BUY", 1, newKey());
        mockMvc.perform(orderRequest(t, fresh))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PRICE_UNAVAILABLE"));
        assertThat(orderCount(t.acntNo())).isEqualTo(1);
        assertThat(dpsBal(t.acntNo())).isEqualTo(PKG_10M - 700_000L);

        MvcResult again = mockMvc.perform(orderRequest(t, processed))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andReturn();
        assertThat(content(again)).isEqualTo(content(first));
        assertThat(orderCount(t.acntNo())).isEqualTo(1);
    }

    @Test
    @DisplayName("내 계좌 조회는 시세를 읽을 수 없어도 막지 않는다: 예수금·계좌 상태를 보여 주고 보유 종목은 매입금액으로 평가해 지연으로 표시한다")
    void accountSummaryStillAnswers() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        putPriceNow(SAMSUNG, 70_000);
        putPriceNow(HYNIX, 50_000);
        mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "BUY", 10, newKey()))).andExpect(status().isCreated());
        mockMvc.perform(orderRequest(t, orderBody(HYNIX, "BUY", 10, newKey()))).andExpect(status().isCreated());

        DockerClientFactory.instance().client().pauseContainerCmd(OWN_REDIS.getContainerId()).exec();
        paused = true;

        mockMvc.perform(get("/api/v1/accounts/me/summary").header("Authorization", bearer(t.tokens())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dpsBal").value(8_800_000))
                .andExpect(jsonPath("$.totalBuyAmt").value(1_200_000))
                .andExpect(jsonPath("$.totalEvalAmt").value(1_200_000))
                .andExpect(jsonPath("$.totalPnl").value(0))
                .andExpect(jsonPath("$.priceDelayed").value(true));
    }

    @Test
    @DisplayName("Redis가 멈춰도 출금은 Redis를 쓰지 않아 그대로 처리된다")
    void withdrawalUnaffected() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        pauseRedis();

        mockMvc.perform(withdrawRequest(t, withdrawBody(1_000_000, newKey(), null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dpsBal").value(9_000_000));
        assertThat(withdrawalCount(t.acntNo())).isEqualTo(1);
        assertThat(dpsBal(t.acntNo())).isEqualTo(PKG_10M - 1_000_000L);
    }

    @Test
    @DisplayName("Redis 호출 시간 제한 200ms가 실제 연결에 걸려 있다: 멈춘 Redis를 부르면 1초 안에 실패한다")
    void redisCommandTimeoutIs200ms() {
        LettuceConnectionFactory lettuce = (LettuceConnectionFactory) redisConnectionFactory;
        assertThat(lettuce.getClientConfiguration().getCommandTimeout()).isEqualTo(Duration.ofMillis(200));

        // 연결을 먼저 열어 둠. 잰 시간에 연결 수립이 섞이지 않게 하기 위해
        redis.opsForValue().get("p7:warmup");
        pauseRedis();

        long started = System.nanoTime();
        assertThatThrownBy(() -> redis.opsForValue().get("p7:timeout")).isInstanceOf(DataAccessException.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(180)).isLessThan(Duration.ofSeconds(1));
    }

    private void pauseRedis() {
        DockerClientFactory.instance().client().pauseContainerCmd(OWN_REDIS.getContainerId()).exec();
        paused = true;
    }
}
