package com.stockshield.common.error;

import java.util.Map;

/**
 * 결과를 저장하지 않고 바로 오류로 응답할 때 던진다 (400·404·409·503 등).
 * 업무 거절(422·423)은 예외로 롤백하지 않고 '거절 결과'로 커밋한 뒤 응답으로 바꾼다. (TRD_LED_001 #3)
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Map<String, Object> detail;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, Map.of());
    }

    public ApiException(ErrorCode errorCode, Map<String, Object> detail) {
        super(errorCode.name());
        this.errorCode = errorCode;
        this.detail = detail;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> detail() {
        return detail;
    }
}
