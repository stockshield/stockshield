package com.stockshield.ledger;

/** 시세 저장소(Redis)에 닿지 못함. 주문은 503 PRICE_UNAVAILABLE로 바꿈 (SYS_CORE_002 #5) */
public class PriceUnavailableException extends RuntimeException {

    public PriceUnavailableException(Throwable cause) {
        super("시세 저장소(Redis)를 읽을 수 없습니다", cause);
    }
}
