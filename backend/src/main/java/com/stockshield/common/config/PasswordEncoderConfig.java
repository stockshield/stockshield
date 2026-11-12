package com.stockshield.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 로그인 비밀번호와 출금 비밀번호를 BCrypt로 저장·비교하는 인코더. 회원(조성우)과 FDS(윤제정)가 이 빈 하나를 함께 쓴다.
 * 저장되는 해시는 $2a$10$로 시작하고 {bcrypt} 같은 접두어가 없다. 인코더가 둘로 갈리면 맞는 비밀번호도 불일치가 되어 정상 계좌가 잠긴다.
 * (USR_AUTH_001 구현방안, SYS_SEC_001 #1, 연결 합의서 2절 규칙 10)
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
