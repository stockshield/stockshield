package com.stockshield.fds.api;

/**
 * 체결(FILLED) 주문 트랜잭션 안에서 원장이 발행한다. 거절 주문과 같은 키 재요청에는 발행하지 않는다.
 * FDS는 커밋 뒤 별도 스레드에서 받는다 (TRD_ORD_001 #6, FDS_TRD_001)
 */
public record OrderFilledEvent(String ordNo) {
}
