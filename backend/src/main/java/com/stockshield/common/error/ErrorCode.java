package com.stockshield.common.error;

import org.springframework.http.HttpStatus;

/**
 * 에러 코드와 HTTP 상태. 화면은 HTTP 상태가 아니라 code로 분기한다.
 * 기준: API 명세서 '공통 규칙' 시트 3. 에러 코드 (code·HTTP·화면 안내 문구)
 * 결과를 저장하는지(멱등키 소모)는 각 기능의 서비스가 정하고, 여기서는 응답 형식만 정한다.
 */
public enum ErrorCode {

    // 400
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
    AUTH_CODE_MISMATCH(HttpStatus.BAD_REQUEST, "인증 코드가 틀렸습니다."),
    // 401
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    // 403 - 권한 부족에만 쓴다
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    // 404
    STOCK_NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없습니다."),
    /** 탐지 기록 없음. 관리자 탐지 조회·확인(조성우)과 AI 재요청(윤제정)이 함께 쓴다 (API 명세서 'API_명세서' 24~26행) */
    DETECTION_NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없습니다."),
    // 409
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "같은 요청 키로 다른 내용을 보낼 수 없습니다. 새로 요청해 주세요."),
    ALREADY_CHECKED(HttpStatus.CONFLICT, "이미 처리되었습니다."),
    SAME_STATUS(HttpStatus.CONFLICT, "이미 처리되었습니다."),
    RECONCILE_RUNNING(HttpStatus.CONFLICT, "이미 처리되었습니다."),
    LGN_ID_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다."),
    AI_RETRY_NOT_ALLOWED(HttpStatus.CONFLICT, "AI 설명이 실패한 건만 다시 요청할 수 있습니다."),
    // 410
    AUTH_CODE_EXPIRED(HttpStatus.GONE, "인증 코드가 만료되었습니다. 처음부터 다시 진행하세요."),
    // 422 - 업무 거절 (결과 저장)
    MARKET_CLOSED(HttpStatus.UNPROCESSABLE_ENTITY, "장 운영 시간(휴장일 제외 평일 09:00~15:30)이 아닙니다."),
    PRICE_STALE(HttpStatus.UNPROCESSABLE_ENTITY, "시세가 지연되어 지금은 주문할 수 없습니다."),
    INSUFFICIENT_BALANCE(HttpStatus.UNPROCESSABLE_ENTITY, "예수금이 부족합니다."),
    INSUFFICIENT_HOLDING(HttpStatus.UNPROCESSABLE_ENTITY, "보유 수량이 부족합니다."),
    WDR_PWD_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "출금 비밀번호가 틀렸습니다."),
    // 423
    ACCOUNT_LOCKED(HttpStatus.LOCKED, "계좌가 잠겨 있습니다. 관리자에게 문의하세요."),
    WDR_PWD_LOCKED(HttpStatus.LOCKED, "출금 비밀번호를 5회 틀려 계좌가 잠겼습니다. 관리자에게 문의하세요."),
    LOGIN_LOCKED(HttpStatus.LOCKED, "비밀번호를 5회 틀려 30분 동안 로그인할 수 없습니다."),
    // 428
    WDR_PWD_REQUIRED(HttpStatus.PRECONDITION_REQUIRED, "큰 금액 출금이라 출금 비밀번호가 필요합니다."),
    // 429
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 시도하세요."),
    // 500 - 전체 롤백
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류입니다."),
    // 503 - 아무것도 저장하지 않음, 같은 요청을 다시 보낼 수 있음
    LOCK_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE, "요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도하세요."),
    PRICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "시세를 불러올 수 없어 지금은 주문할 수 없습니다."),
    AUTH_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "지금은 인증할 수 없습니다."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "서비스 점검 중입니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}
