package com.stockshield.ledger;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockshield.common.config.ClockConfig;

/**
 * 시세 컨슈머가 쓴 price:{tckCd}에서 currentPrice·prcTs만 읽음 (연결 합의서 9절, 문서 수정 대기 목록 #56).
 * 주문 체결가·시세 지연 판단과 내 계좌 평가에 씀. 외부 API는 부르지 않음 (SYS_CORE_001 #2).
 */
@Component
public class PriceReader {

    /** @param prcTs Redis에 쓴 시각이 아니라 메시지의 시세 시각(Asia/Seoul) */
    public record Quote(long currentPrice, LocalDateTime prcTs) {
    }

    static final String PRICE_KEY = "price:";
    static final Duration STALE_AFTER = Duration.ofSeconds(60);
    /** 최대 주문 수량(1,000,000)을 곱해도 long을 넘지 않는 값. NUMERIC(15,0)에도 들어감 */
    static final long MAX_PRICE = Long.MAX_VALUE / 1_000_000L;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public PriceReader(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * 키가 없거나 값을 해석할 수 없으면 비어 있음(쓸 수 있는 시세가 없는 것, 주문은 PRICE_STALE).
     * Redis 호출 자체가 실패하면(연결 실패, 200ms 타임아웃) PriceUnavailableException.
     */
    public Optional<Quote> read(String tckCd) {
        String json;
        try {
            json = redis.opsForValue().get(PRICE_KEY + tckCd);
        } catch (RuntimeException e) {
            throw new PriceUnavailableException(e);
        }
        return parse(json);
    }

    /** 60초를 넘겨야 지연. 정확히 60초는 지연이 아님 (TRD_ORD_001 #2 '60초 넘게') */
    public boolean isStale(Quote quote, LocalDateTime now) {
        return Duration.between(quote.prcTs(), now).compareTo(STALE_AFTER) > 0;
    }

    private Optional<Quote> parse(String json) {
        if (json == null) {
            return Optional.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode price = node.path("currentPrice");
            if (!price.isIntegralNumber() || !price.canConvertToLong()
                    || price.asLong() <= 0 || price.asLong() > MAX_PRICE) {
                return Optional.empty();
            }
            LocalDateTime prcTs = OffsetDateTime.parse(node.path("prcTs").asText())
                    .atZoneSameInstant(ClockConfig.ZONE).toLocalDateTime();
            return Optional.of(new Quote(price.asLong(), prcTs));
        } catch (JsonProcessingException | DateTimeException e) {
            return Optional.empty();
        }
    }
}
