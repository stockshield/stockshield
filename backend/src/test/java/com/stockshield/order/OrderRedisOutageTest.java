package com.stockshield.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.stockshield.support.LedgerTestSupport;

/**
 * Redis 장애 중 주문 (SYS_CORE_002 #5, TRD_ORD_001 #2·#3, API 명세서 'API_명세서' 17행).
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
}
