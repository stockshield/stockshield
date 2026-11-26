package com.stockshield.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 회원과 FDS가 함께 쓰는 인코더의 저장 형식. 형식이 어긋나면 맞는 출금 비밀번호도 불일치가 되어 정상 계좌가 잠긴다.
 * (USR_AUTH_001 구현방안, SYS_SEC_001 #1, 연결 합의서 2절 규칙 10)
 */
class PasswordEncoderConfigTest {

    private final PasswordEncoder encoder = new PasswordEncoderConfig().passwordEncoder();

    @Test
    @DisplayName("BCrypt 해시는 $2a$10$로 시작하고 {bcrypt} 같은 접두어가 없다")
    void hashFormatIsPlainBcrypt() {
        String hash = encoder.encode("123456");

        assertThat(hash).startsWith("$2a$10$").doesNotStartWith("{");
        assertThat(hash).isNotEqualTo(encoder.encode("123456"));   // 솔트가 달라 매번 다른 해시
    }

    @Test
    @DisplayName("저장한 해시와 맞는 비밀번호만 일치한다")
    void matchesOnlyTheRightPassword() {
        String hash = encoder.encode("123456");

        assertThat(encoder.matches("123456", hash)).isTrue();
        assertThat(encoder.matches("654321", hash)).isFalse();
    }
}
