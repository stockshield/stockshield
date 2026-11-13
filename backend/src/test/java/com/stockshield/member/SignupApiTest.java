package com.stockshield.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import com.stockshield.ledger.AccountNumbers;
import com.stockshield.support.IntegrationTestSupport;

/**
 * 회원가입·가입 이메일 인증·아이디 중복 확인을 실제 PostgreSQL·Redis·FDS(신뢰 접속 등록)로 확인한다.
 * USR_AUTH_001 #1~#4, API 명세서 'API_명세서' 2~4행, 문서 수정 대기 목록 #15·#16·#18·#20·#24
 */
class SignupApiTest extends IntegrationTestSupport {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("가입: 회원(ROLE_USER)·계좌(패키지 금액, ACTIVE, Luhn 계좌번호)·첫 신뢰 접속(SIGNUP)을 만들고 토큰은 주지 않는다")
    void signUpCreatesMemberAccountAndTrustedAccess() throws Exception {
        String lgnId = uniqueLgnId();
        String email = emailOf(lgnId);
        String device = newDeviceId();
        String authId = requestSignupCode(email);
        Map<String, Object> body = signupBody(lgnId, "  " + email + "  ", authId, fakeMail.lastCodeTo(email));
        body.put("mbrNm", "  홍길동 ");
        body.put("initPkgCd", "PKG_30M");

        MvcResult r = mockMvc.perform(signupRequest(body, device, "198.51.100.21")
                        .header("User-Agent", "Mozilla/5.0 Test"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.initDpsAmt").value(30_000_000))
                .andExpect(jsonPath("$.dpsBal").value(30_000_000))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andReturn();
        long mbrNo = body(r).path("mbrNo").asLong();
        String acntNo = body(r).path("acntNo").asText();

        assertThat(acntNo).hasSize(12).startsWith("100");
        assertThat(AccountNumbers.isValid(acntNo)).isTrue();

        Map<String, Object> member = jdbcTemplate.queryForMap("SELECT * FROM member WHERE mbr_no = ?", mbrNo);
        assertThat(member.get("lgn_id")).isEqualTo(lgnId);
        assertThat(member.get("mbr_nm")).isEqualTo("홍길동");
        assertThat(member.get("eml_addr")).isEqualTo(email);
        assertThat(member.get("role_cd")).isEqualTo("ROLE_USER");
        assertThat((String) member.get("lgn_pwd")).startsWith("$2a$10$");
        assertThat(passwordEncoder.matches(TEST_PASSWORD, (String) member.get("lgn_pwd"))).isTrue();

        Map<String, Object> account = jdbcTemplate.queryForMap("SELECT * FROM account WHERE acnt_no = ?", acntNo);
        assertThat(((Number) account.get("mbr_no")).longValue()).isEqualTo(mbrNo);
        assertThat(account.get("init_pkg_cd")).isEqualTo("PKG_30M");
        assertThat(((Number) account.get("init_dps_amt")).longValue()).isEqualTo(30_000_000L);
        assertThat(((Number) account.get("dps_bal")).longValue()).isEqualTo(30_000_000L);
        assertThat(account.get("acnt_stat_cd")).isEqualTo("ACTIVE");
        assertThat(((Number) account.get("wdr_pwd_fail_cnt")).intValue()).isZero();
        assertThat(passwordEncoder.matches(TEST_WDR_PWD, (String) account.get("wdr_pwd"))).isTrue();

        Map<String, Object> trusted = jdbcTemplate.queryForMap("SELECT * FROM trusted_access WHERE mbr_no = ?", mbrNo);
        assertThat(trusted.get("reg_dv_cd")).isEqualTo("SIGNUP");
        assertThat(trusted.get("conn_ip")).isEqualTo("198.51.100.21");
        assertThat(trusted.get("dvc_id")).isEqualTo(device);
        assertThat(trusted.get("user_agt")).isEqualTo("Mozilla/5.0 Test");
    }

    @Test
    @DisplayName("입력 규칙을 어기면 400이고 인증 코드를 쓰지 않는다(같은 코드로 바로 가입할 수 있다)")
    void invalidInputDoesNotConsumeCode() throws Exception {
        String lgnId = uniqueLgnId();
        String email = emailOf(lgnId);
        String authId = requestSignupCode(email);
        String code = fakeMail.lastCodeTo(email);

        List<Consumer<Map<String, Object>>> breaks = List.of(
                b -> b.put("lgnId", "Upper1"),
                b -> b.put("lgnId", "1abc"),
                b -> b.put("lgnPwd", "short1!"),
                b -> b.put("lgnPwd", "NoSpecial123"),
                b -> b.put("lgnPwd", "Aa1!" + "x".repeat(69)),
                b -> b.put("mbrNm", "   "),
                b -> b.put("mbrNm", "가".repeat(51)),
                b -> b.put("emlAddr", "not-an-email"),
                b -> b.put("authCode", "12345"),
                b -> b.put("wdrPwd", "12345a"),
                b -> b.put("wdrPwd", "1234567"),
                b -> b.put("initPkgCd", "PKG_20M"),
                b -> b.remove("lgnPwd"));
        for (Consumer<Map<String, Object>> broken : breaks) {
            Map<String, Object> b = signupBody(lgnId, email, authId, code);
            broken.accept(b);
            mockMvc.perform(signupRequest(b, newDeviceId(), DEFAULT_IP))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }

        mockMvc.perform(signupRequest(signupBody(lgnId, email, authId, code), newDeviceId(), DEFAULT_IP))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("X-Device-Id가 없거나 UUID 형식이 아니면 400")
    void deviceIdRequired() throws Exception {
        String lgnId = uniqueLgnId();
        String email = emailOf(lgnId);
        String authId = requestSignupCode(email);
        Map<String, Object> b = signupBody(lgnId, email, authId, fakeMail.lastCodeTo(email));

        mockMvc.perform(signupRequest(b, null, DEFAULT_IP)).andExpect(status().isBadRequest());
        mockMvc.perform(signupRequest(b, "device-1", DEFAULT_IP)).andExpect(status().isBadRequest());
        mockMvc.perform(signupRequest(b, newDeviceId(), DEFAULT_IP)).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("아이디 중복은 409 LGN_ID_DUPLICATED이고 인증 코드를 쓰지 않는다")
    void duplicateLgnIdDoesNotConsumeCode() throws Exception {
        TestMember existing = signUp();
        String email = emailOf(uniqueLgnId());
        String authId = requestSignupCode(email);
        String code = fakeMail.lastCodeTo(email);

        mockMvc.perform(get("/api/v1/members/check-id").param("lgnId", existing.lgnId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isAvailable").value(false));
        mockMvc.perform(signupRequest(signupBody(existing.lgnId(), email, authId, code), newDeviceId(), DEFAULT_IP))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LGN_ID_DUPLICATED"));

        String newId = uniqueLgnId();
        mockMvc.perform(get("/api/v1/members/check-id").param("lgnId", newId))
                .andExpect(jsonPath("$.isAvailable").value(true));
        mockMvc.perform(signupRequest(signupBody(newId, email, authId, code), newDeviceId(), DEFAULT_IP))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("아이디 중복 확인: 형식이 틀린 아이디는 400")
    void checkIdValidatesFormat() throws Exception {
        mockMvc.perform(get("/api/v1/members/check-id").param("lgnId", "Bad-Id"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("인증한 이메일과 다른 이메일로 가입하면 코드가 틀린 것과 같이 400(남은 횟수)이고 오류 횟수에 센다. 5회째는 410")
    void emailBoundToCode() throws Exception {
        String lgnId = uniqueLgnId();
        String email = emailOf(lgnId);
        String authId = requestSignupCode(email);
        String code = fakeMail.lastCodeTo(email);
        String wrongCode = code.equals("000000") ? "111111" : "000000";

        // 다른 이메일 + 맞는 코드
        mockMvc.perform(signupRequest(signupBody(lgnId, "other-" + email, authId, code), newDeviceId(), DEFAULT_IP))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH_CODE_MISMATCH"))
                .andExpect(jsonPath("$.detail.remainCnt").value(4));
        // 같은 이메일 + 틀린 코드
        for (int remain = 3; remain >= 1; remain--) {
            mockMvc.perform(signupRequest(signupBody(lgnId, email, authId, wrongCode), newDeviceId(), DEFAULT_IP))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail.remainCnt").value(remain));
        }
        // 5회째 → 410, 이후 맞는 코드도 410 (다시 발송받아야 한다)
        mockMvc.perform(signupRequest(signupBody(lgnId, email, authId, wrongCode), newDeviceId(), DEFAULT_IP))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("AUTH_CODE_EXPIRED"));
        mockMvc.perform(signupRequest(signupBody(lgnId, email, authId, code), newDeviceId(), DEFAULT_IP))
                .andExpect(status().isGone());
    }

    @Test
    @DisplayName("가입 코드는 발송부터 5분: 4분 59초까지 쓸 수 있고 5분 정각부터 410 (고정 Clock)")
    void signupCodeValidForFiveMinutes() throws Exception {
        LocalDateTime t = LocalDateTime.of(2026, 11, 13, 10, 0, 0);

        testClock.set(t);
        String okId = uniqueLgnId();
        String okAuthId = requestSignupCode(emailOf(okId));
        String okCode = fakeMail.lastCodeTo(emailOf(okId));
        String lateId = uniqueLgnId();
        String lateAuthId = requestSignupCode(emailOf(lateId));
        String lateCode = fakeMail.lastCodeTo(emailOf(lateId));

        testClock.set(t.plusMinutes(5).minusSeconds(1));
        mockMvc.perform(signupRequest(signupBody(okId, emailOf(okId), okAuthId, okCode), newDeviceId(), DEFAULT_IP))
                .andExpect(status().isCreated());

        testClock.set(t.plusMinutes(5));
        mockMvc.perform(signupRequest(signupBody(lateId, emailOf(lateId), lateAuthId, lateCode), newDeviceId(), DEFAULT_IP))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("AUTH_CODE_EXPIRED"));
    }

    @Test
    @DisplayName("다시 발송: 같은 주소 60초 안에는 429, 60초가 지나면 새 authId를 주고 이전 코드는 무효")
    void resendIntervalAndPreviousCodeInvalidated() throws Exception {
        LocalDateTime t = LocalDateTime.of(2026, 11, 13, 11, 0, 0);
        String lgnId = uniqueLgnId();
        String email = emailOf(lgnId);

        testClock.set(t);
        String firstAuthId = requestSignupCode(email);
        String firstCode = fakeMail.lastCodeTo(email);

        testClock.set(t.plusSeconds(59));
        mockMvc.perform(emailCodeRequest(email))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));

        testClock.set(t.plusSeconds(60));
        String secondAuthId = requestSignupCode(email);
        String secondCode = fakeMail.lastCodeTo(email);
        assertThat(secondAuthId).isNotEqualTo(firstAuthId);

        mockMvc.perform(signupRequest(signupBody(lgnId, email, firstAuthId, firstCode), newDeviceId(), DEFAULT_IP))
                .andExpect(status().isGone());
        mockMvc.perform(signupRequest(signupBody(lgnId, email, secondAuthId, secondCode), newDeviceId(), DEFAULT_IP))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("같은 authId로 동시에 가입하면 1건만 성공하고 같은 이메일의 회원은 하나다 (코드 1회용)")
    void sameCodeConcurrentSignupOnlyOneSucceeds() throws Exception {
        String email = emailOf(uniqueLgnId());
        String authId = requestSignupCode(email);
        String code = fakeMail.lastCodeTo(email);
        int threads = 8;

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Map<String, Object> b = signupBody(uniqueLgnId(), email, authId, code);
                Callable<Integer> call = () -> {
                    start.await();
                    return mockMvc.perform(signupRequest(b, newDeviceId(), DEFAULT_IP)).andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
            assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
            assertThat(statuses).filteredOn(s -> s == 410).hasSize(threads - 1);
        } finally {
            pool.shutdownNow();
        }
        Integer members = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM member WHERE eml_addr = ?", Integer.class, email);
        assertThat(members).isEqualTo(1);
    }

    @Test
    @DisplayName("메일을 보낼 수 없으면 503 AUTH_UNAVAILABLE이고, 메일이 살아나면 60초를 기다리지 않고 다시 요청할 수 있다")
    void mailFailureIs503() throws Exception {
        String email = emailOf(uniqueLgnId());
        fakeMail.setFailing(true);
        mockMvc.perform(emailCodeRequest(email))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_UNAVAILABLE"));

        fakeMail.setFailing(false);
        mockMvc.perform(emailCodeRequest(email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ttlSec").value(300));
    }
}
