package com.stockshield.auth;

/**
 * 인증에 쓰는 Redis(auth:*)를 읽거나 쓸 수 없을 때. 서비스가 규칙별 응답으로 바꾼다.
 * 아는 기기 로그인은 Access Token만 주고, 새 접속·인증 확인·재발송·재발급은 503 AUTH_UNAVAILABLE (USR_AUTH_002 #5, FDS_LGN_001 #5).
 * DB 예외와 섞이지 않게 Redis 호출을 감싼 곳에서만 던진다.
 */
public class RedisUnavailableException extends RuntimeException {

    public RedisUnavailableException(Throwable cause) {
        super("인증 저장소(Redis)를 쓸 수 없습니다", cause);
    }
}
