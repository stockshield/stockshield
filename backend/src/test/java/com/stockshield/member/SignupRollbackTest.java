package com.stockshield.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.stockshield.support.FakeFdsConfig;
import com.stockshield.support.IntegrationTestSupport;
import com.stockshield.support.ScriptedFdsService;

/**
 * 가입 트랜잭션의 원자성: 회원 저장·계좌 개설·첫 기기 등록 중 하나가 실패하면 모두 되돌려 계좌 없는 회원이 생기지 않는다.
 * USR_AUTH_001 #4·구현방안('하나의 트랜잭션'), 합의서 2절 표(registerSignupAccess는 가입 트랜잭션에 참여, MANDATORY)
 *
 * 마지막 단계(첫 기기 등록)를 실패시켜야 해서 FDS 결과를 테스트가 정하는 가짜(FakeFdsConfig)를 쓴다(롤백 테스트, 합의서 12절).
 */
@Import(FakeFdsConfig.class)
class SignupRollbackTest extends IntegrationTestSupport {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ScriptedFdsService fds;

    @AfterEach
    void resetFds() {
        fds.reset();
    }

    @Test
    @DisplayName("첫 기기 등록에서 실패하면 회원·계좌가 모두 남지 않고, 쓴 인증 코드는 되살리지 않는다(다시 발송)")
    void failureInLastStepRollsBackMemberAndAccount() throws Exception {
        String lgnId = uniqueLgnId();
        String email = emailOf(lgnId);
        String authId = requestSignupCode(email);
        String code = fakeMail.lastCodeTo(email);
        fds.failSignupWith(new IllegalStateException("테스트: 신뢰 접속 등록 실패"));

        mockMvc.perform(signupRequest(signupBody(lgnId, email, authId, code), newDeviceId(), DEFAULT_IP))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM member WHERE lgn_id = ?", Integer.class, lgnId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account a JOIN member m ON m.mbr_no = a.mbr_no WHERE m.eml_addr = ?",
                Integer.class, email)).isZero();

        // 코드는 1회용이라 같은 코드로 다시 가입할 수 없다 (문서 수정 대기 목록 #24)
        fds.reset();
        mockMvc.perform(signupRequest(signupBody(lgnId, email, authId, code), newDeviceId(), DEFAULT_IP))
                .andExpect(status().isGone());

        // 다시 발송받으면 가입된다
        testClock.advance(java.time.Duration.ofSeconds(61));
        String newAuthId = requestSignupCode(email);
        Map<String, Object> retry = signupBody(lgnId, email, newAuthId, fakeMail.lastCodeTo(email));
        mockMvc.perform(signupRequest(retry, newDeviceId(), DEFAULT_IP))
                .andExpect(status().isCreated());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM member WHERE lgn_id = ?", Integer.class, lgnId))
                .isEqualTo(1);
    }
}
