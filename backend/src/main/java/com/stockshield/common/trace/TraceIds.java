package com.stockshield.common.trace;

import org.slf4j.MDC;

/** 현재 요청의 거래추적번호를 꺼내는 곳. 주문·출금 행의 trc_id도 여기서 얻는다. */
public final class TraceIds {

    public static final String MDC_KEY = "traceId";

    private TraceIds() {
    }

    /** 요청 밖(스케줄러 등)에서 부르면 null */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
