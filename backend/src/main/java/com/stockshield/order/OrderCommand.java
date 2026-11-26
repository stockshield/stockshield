package com.stockshield.order;

/** 검증을 마친 주문 요청. idmpKey는 소문자 UUID v4 */
record OrderCommand(String tckCd, String ordDvCd, long ordQty, String idmpKey) {
}
