package com.stockshield.common.error;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.stockshield.common.trace.TraceIds;

import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import jakarta.validation.ConstraintViolationException;

/**
 * 모든 오류를 공통 에러 형식(code·message·detail·traceId)으로 바꾼다. (API 명세서 공통 규칙 2·3)
 * 시스템 오류(락 대기 초과, 저장소 장애, 예상하지 못한 오류)는 트랜잭션이 이미 롤백된 뒤 여기로 온다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 결과를 저장하지 않는 오류 (서비스가 직접 던짐) */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException e) {
        return build(e.errorCode(), e.detail());
    }

    /** @Validated 메서드 인자 검증 실패 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e) {
        return build(ErrorCode.INVALID_REQUEST, Map.of());
    }

    /**
     * 계좌 락 대기 2초 초과 (PostgreSQL lock_timeout, SQLSTATE 55P03).
     * 아무것도 저장하지 않았으므로 같은 요청을 다시 보낼 수 있다. (TRD_LED_001 #2, SYS_CORE_001 #7)
     */
    @ExceptionHandler({PessimisticLockingFailureException.class, PessimisticLockException.class, LockTimeoutException.class})
    public ResponseEntity<ErrorResponse> handleLockTimeout(Exception e) {
        log.warn("계좌 락 대기 초과: {}", e.getMessage());
        return build(ErrorCode.LOCK_TIMEOUT, Map.of());
    }

    /** DB 등 필수 구성 요소 장애 */
    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    public ResponseEntity<ErrorResponse> handleResourceFailure(Exception e) {
        log.error("필수 구성 요소 장애", e);
        return build(ErrorCode.SERVICE_UNAVAILABLE, Map.of());
    }

    /** 예상하지 못한 오류 (전체 롤백) */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("예상하지 못한 오류", e);
        return build(ErrorCode.INTERNAL_ERROR, Map.of());
    }

    /**
     * Spring MVC 기본 오류(본문 형식 오류, 필수 헤더 누락, 없는 경로 등)도 같은 형식으로 바꾼다.
     * HTTP 상태는 Spring이 정한 값을 유지하고, 404는 RESOURCE_NOT_FOUND, 그 밖의 4xx는 INVALID_REQUEST로 둔다.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ErrorCode errorCode;
        if (statusCode.value() == 404) {
            errorCode = ErrorCode.RESOURCE_NOT_FOUND;
        } else if (statusCode.is4xxClientError()) {
            errorCode = ErrorCode.INVALID_REQUEST;
        } else {
            errorCode = ErrorCode.INTERNAL_ERROR;
            log.error("요청 처리 오류", ex);
        }
        ErrorResponse response = ErrorResponse.of(errorCode, Map.of(), TraceIds.current());
        return ResponseEntity.status(statusCode).headers(headers).body(response);
    }

    private ResponseEntity<ErrorResponse> build(ErrorCode errorCode, Map<String, Object> detail) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(errorCode.status());
        if (errorCode == ErrorCode.LOCK_TIMEOUT) {
            builder.header(HttpHeaders.RETRY_AFTER, "1");   // API 명세서 공통 규칙: 503 LOCK_TIMEOUT일 때 1(초)
        }
        return builder.body(ErrorResponse.of(errorCode, detail, TraceIds.current()));
    }
}
