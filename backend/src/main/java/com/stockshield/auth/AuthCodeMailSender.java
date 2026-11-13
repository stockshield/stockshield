package com.stockshield.auth;

/**
 * 인증 코드 메일 발송. 실패하면 Spring의 MailException을 던진다(서비스가 503 AUTH_UNAVAILABLE로 바꾼다).
 * DB 트랜잭션 밖에서만 부른다 (연결 합의서 2절 규칙 3·5절). 테스트는 이 인터페이스의 가짜를 쓴다.
 */
public interface AuthCodeMailSender {

    void send(String to, String code, AuthPurpose purpose);
}
