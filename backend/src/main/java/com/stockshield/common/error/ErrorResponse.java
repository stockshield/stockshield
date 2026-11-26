package com.stockshield.common.error;

import java.util.Map;

/**
 * 2xx가 아닌 모든 응답의 본문 형식.
 * { "code": "...", "message": "...", "detail": { ... }, "traceId": "..." }
 * (API 명세서 공통 규칙 2. 에러 응답 형식)
 *
 * @param detail 거절을 저장한 경우 ordNo·wdrNo와 상태, 그 밖의 추가 정보(remainCnt 등). 없으면 빈 객체
 * @param traceId 결과를 처음 만든 요청의 거래추적번호
 */
public record ErrorResponse(String code, String message, Map<String, Object> detail, String traceId) {

    public static ErrorResponse of(ErrorCode errorCode, Map<String, Object> detail, String traceId) {
        return new ErrorResponse(errorCode.name(), errorCode.message(), detail == null ? Map.of() : detail, traceId);
    }
}
