package com.stockshield.market;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 최신 시세 캐시 (MKT_TICK_002 #1). Redis에는 시세 컨슈머 한 곳에서만 쓴다(MKT_TICK_001 #1).
 *
 * price:{tckCd} = {"tckCd","currentPrice","prcTs", ...} (연결 합의서 9절 합의 18)
 * - StringRedisTemplate으로 UTF-8 JSON 문자열을 그대로 쓴다. 타입 정보(@class)는 넣지 않는다.
 * - 조성우의 주문·내 계좌는 currentPrice(정수, 원)와 prcTs(메시지의 시세 시각, +09:00)만 읽는다.
 *   prcTs는 Redis에 쓴 시각이 아니라 메시지의 시각이라, Kafka 장애 뒤 밀린 옛 시세로 체결되지 않는다.
 * - 나머지 필드(chgPrc, chgRt, totalTrdVol = 누적 거래량)는 화면용으로 덧붙인다.
 * index:{idxCd} = {"idxCd","currentVal","chgVal","chgRt","prcTs"}
 */
@Component
public class PriceCache {

    public static final String PRICE_KEY = "price:";
    public static final String INDEX_KEY = "index:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public PriceCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void putPrice(MarketTick t) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("tckCd", t.code());
        v.put("currentPrice", t.currentPrice());
        v.put("prcTs", t.prcTs());
        v.put("chgPrc", t.chgPrc());
        v.put("chgRt", t.chgRt());
        v.put("totalTrdVol", t.accVol());
        redis.opsForValue().set(PRICE_KEY + t.code(), json(v));
    }

    public void putIndex(MarketTick t) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("idxCd", t.code());
        v.put("currentVal", t.idxVal());
        v.put("chgVal", t.chgPrc());
        v.put("chgRt", t.chgRt());
        v.put("prcTs", t.prcTs());
        redis.opsForValue().set(INDEX_KEY + t.code(), json(v));
    }

    private String json(Map<String, Object> v) {
        try {
            return objectMapper.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("시세를 JSON으로 바꾸지 못했습니다", e);
        }
    }
}
