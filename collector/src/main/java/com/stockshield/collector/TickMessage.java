package com.stockshield.collector;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Kafka market.tick 메시지 (MKT_TICK_001 #3). 메시지 키는 종목·지수 코드다.
 *
 * - type: TICK(종목 체결) 또는 INDEX(지수)
 * - currentPrice: 종목 현재가(원, 정수). INDEX는 idxVal(지수, 소수)과 idxChg(전일 대비 포인트, 소수)를 쓴다
 * - 지수 코드: 0001 코스피, 1001 코스닥 (메인 서버 MarketQueryService.KOSPI·KOSDAQ)
 * - prcTs: 시세 시각(ISO 8601, +09:00). 재생 시세는 보내는 시점의 시각이고 원래 체결 시각은 orgTs에 담는다
 * - src: KIS(실시간), MOCK(가짜), REPLAY(재생)
 * 메인 서버의 시세 컨슈머가 이 형식을 읽는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TickMessage(int v, String type, String code, Long currentPrice, BigDecimal idxVal,
                          Long chgPrc, BigDecimal chgRt, Long trdVol, Long accVol,
                          String prcTs, String orgTs, String src, BigDecimal idxChg) {

    public static final int VERSION = 1;

    public static TickMessage tick(String code, long currentPrice, long chgPrc, BigDecimal chgRt,
                                   long trdVol, long accVol, String prcTs, String src) {
        return new TickMessage(VERSION, "TICK", code, currentPrice, null, chgPrc, chgRt, trdVol, accVol,
                prcTs, null, src, null);
    }

    /** 지수 시세. 전일 대비는 소수 포인트라 chgPrc(정수 원) 대신 idxChg에 담는다 */
    public static TickMessage index(String code, BigDecimal idxVal, BigDecimal idxChg, BigDecimal chgRt,
                                    Long accVol, String prcTs, String src) {
        return new TickMessage(VERSION, "INDEX", code, null, idxVal, null, chgRt, null, accVol,
                prcTs, null, src, idxChg);
    }

    /** 재생: 시세 시각을 지금으로 바꾸고 원래 체결 시각을 orgTs에 남긴다 */
    public TickMessage replayedAt(String nowTs) {
        return new TickMessage(v, type, code, currentPrice, idxVal, chgPrc, chgRt, trdVol, accVol,
                nowTs, orgTs != null ? orgTs : prcTs, "REPLAY", idxChg);
    }
}
