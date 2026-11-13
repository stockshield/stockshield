package com.stockshield.market;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Kafka market.tick 메시지 (시세 수집기가 보냄, MKT_TICK_001 #3). 메시지 키는 종목·지수 코드다.
 * type은 TICK(종목) 또는 INDEX(지수), prcTs는 시세 시각(ISO 8601 +09:00), 재생 시세의 원래 체결 시각은 orgTs.
 * 모르는 필드는 무시해서 수집기가 필드를 늘려도 컨슈머가 깨지지 않게 한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketTick(int v, String type, String code, Long currentPrice, BigDecimal idxVal,
                         Long chgPrc, BigDecimal chgRt, Long trdVol, Long accVol,
                         String prcTs, String orgTs, String src) {

    public boolean isTick() {
        return "TICK".equals(type);
    }

    public boolean isIndex() {
        return "INDEX".equals(type);
    }
}
