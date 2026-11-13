package com.stockshield.fds.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockshield.common.config.ClockConfig;
import com.stockshield.fds.risk.StockRiskJudge.Risk;
import com.stockshield.support.IntegrationTestSupport;

/**
 * FDS ④ 위험 의심 종목 판단을 실제 Redis 7.2.17에서 확인한다.
 * FDS_MKT_001 #1(Z-Score 3·표본 30개, 전일 대비 ±10%), #3(TTL 5분), #4(시세 60초 지연 시 표시 해제), MKT_TICK_002 #2
 * 시각을 움직이는 판단은 컨텍스트의 빈 대신 테스트 시계로 직접 만든 인스턴스로 확인한다.
 */
class StockRiskJudgeTest extends IntegrationTestSupport {

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

    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper objectMapper;

    MovableClock clock;
    StockRiskJudge judge;
    final LocalDateTime open = LocalDateTime.of(2026, 11, 13, 9, 0);

    @BeforeEach
    void setUp() {
        clock = new MovableClock(open.atZone(ClockConfig.ZONE).toInstant());
        judge = new StockRiskJudge(redis, objectMapper, event -> { }, clock);
    }

    @Test
    @DisplayName("전일 대비 +9.99%는 아니고 +10.00%는 급등(UP), −10.00%는 급락(DOWN). risk:stock은 TTL 5분")
    void changeRateBoundary() {
        assertThat(minute("100001", 2, "9.99")).isEmpty();
        Optional<Risk> up = minute("100002", 2, "10.00");
        assertThat(up).hasValueSatisfying(r -> {
            assertThat(r.dir()).isEqualTo("UP");
            assertThat(r.reason()).contains("전일 대비 +10.00%");
        });
        assertThat(minute("100003", 2, "-10.00")).hasValueSatisfying(r -> assertThat(r.dir()).isEqualTo("DOWN"));

        assertThat(redis.opsForValue().get("risk:stock:100002")).contains("\"riskYn\":\"Y\"").contains("\"dir\":\"UP\"");
        Long ttl = redis.getExpire("risk:stock:100002");
        assertThat(ttl).isBetween(290L, 300L);
        assertThat(redis.hasKey("risk:stock:100001")).isFalse();
    }

    @Test
    @DisplayName("수익률 표본 30개가 쌓인 뒤 1분 수익률 Z-Score 3 이상이면 급등, 표본 29개면 판단하지 않는다")
    void zScoreNeedsThirtySamples() {
        // 29개 표본 뒤 급등: Z를 계산하지 않으므로 위험 의심 아님
        assertThat(spikeAfter("200001", 29)).isEmpty();
        // 30개 표본 뒤 급등: Z ≥ 3
        Optional<Risk> r = spikeAfter("200002", 30);
        assertThat(r).hasValueSatisfying(x -> {
            assertThat(x.dir()).isEqualTo("UP");
            assertThat(x.reason()).contains("1분 수익률").contains("Z=");
        });
        assertThat(redis.<String, String>opsForHash().entries("stat:ret:200002")).containsKeys("n", "mean", "std", "z");
    }

    @Test
    @DisplayName("시세가 60초 넘게 끊기면 sweep이 표시를 내리고, 이어지는 동안은 유지한다")
    void sweepRemovesStaleRisk() {
        minute("300001", 2, "12.00");
        assertThat(judge.isActive("300001")).isTrue();

        clock.set(open.plusMinutes(2).plusSeconds(59));   // 마지막 시세(09:02:00)에서 59초
        judge.sweep();
        assertThat(judge.isActive("300001")).isTrue();

        clock.set(open.plusMinutes(3));                    // 60초
        judge.sweep();
        assertThat(judge.isActive("300001")).isFalse();
        assertThat(redis.hasKey("risk:stock:300001")).isFalse();
    }

    @Test
    @DisplayName("Z-Score 계산: 이번 수익률을 넣기 전 30개의 평균·표본표준편차로 구한다")
    void zFormula() {
        ReturnWindow.MinuteReturn m = new ReturnWindow.MinuteReturn("202611130930", 0.01, 30, 0.0, 0.002);
        assertThat(m.z()).hasValueSatisfying(z -> assertThat(z).isEqualTo(5.0, org.assertj.core.data.Offset.offset(1e-9)));
        assertThat(new ReturnWindow.MinuteReturn("m", 0.01, 29, 0.0, 0.002).z()).isEmpty();
        assertThat(new ReturnWindow.MinuteReturn("m", 0.01, 30, 0.0, 0.0).z()).isEmpty();
    }

    // ------------------------------------------------------------------ 도우미

    /** n분 동안 같은 등락률로 체결하고 다음 분 체결로 마지막 분을 닫는다. 마지막 판단 결과를 돌려준다 */
    private Optional<Risk> minute(String code, int minutes, String chgRt) {
        Optional<Risk> last = Optional.empty();
        for (int i = 0; i <= minutes; i++) {
            Optional<Risk> r = judge.onTick(code, 100_000 + i, new BigDecimal(chgRt), ts(i));
            if (r.isPresent()) {
                last = r;
            }
        }
        return last;
    }

    /** 0.1%씩 오르내리는 수익률 samples개를 쌓은 뒤 +2% 급등한 분을 닫는다 */
    private Optional<Risk> spikeAfter(String code, int samples) {
        long price = 100_000;
        int m = 0;
        judge.onTick(code, price, BigDecimal.ZERO, ts(m++));            // 첫 분 (기준 종가)
        for (int i = 0; i < samples; i++) {
            price = i % 2 == 0 ? price + 100 : price - 100;
            judge.onTick(code, price, BigDecimal.ZERO, ts(m++));        // 이 체결이 직전 분을 닫는다
        }
        judge.onTick(code, Math.round(price * 1.02), BigDecimal.ZERO, ts(m++));   // 급등한 분
        return judge.onTick(code, Math.round(price * 1.02), BigDecimal.ZERO, ts(m)); // 급등한 분을 닫는다
    }

    private String ts(int minute) {
        return open.plusMinutes(minute).atZone(ClockConfig.ZONE).toOffsetDateTime().toString();
    }

    static final class MovableClock extends Clock {
        private Instant instant;

        MovableClock(Instant start) {
            this.instant = start;
        }

        void set(LocalDateTime t) {
            instant = t.atZone(ClockConfig.ZONE).toInstant();
        }

        @Override
        public ZoneId getZone() {
            return ClockConfig.ZONE;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
