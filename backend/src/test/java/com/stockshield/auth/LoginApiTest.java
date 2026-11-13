package com.stockshield.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import com.stockshield.support.IntegrationTestSupport;

/**
 * 로그인·새 접속 이메일 인증·재발송을 실제 PostgreSQL·Redis·FDS(①)로 확인한다.
 * USR_AUTH_002 #1~#3, FDS_LGN_001 #1~#5, API 명세서 'API_명세서' 5~7행, 연결 합의서 5절(로그인 순서·인증 유효 시간·5분 경계),
 * 문서 수정 대기 목록 #13·#30·#31·#48·#49
 */
class LoginApiTest extends IntegrationTestSupport {

    static final String CAFE_IP = "203.0.113.50";

    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("가입한 IP·기기로 로그인하면 바로 200 토큰(Access·Refresh·권한)이고 이력은 성공·추가 인증 없음")
    void knownAccessGetsTokens() throws Exception {
        TestMember m = signUp();

        mockMvc.perform(loginRequest(m.lgnId(), TEST_PASSWORD, m.deviceId(), m.ip()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.roleCd").value("ROLE_USER"));

        assertThat(history(m.lgnId())).containsExactly(Map.of("succ_yn", "Y", "add_auth_yn", "N"));
        assertThat(detections(m.mbrNo())).isEmpty();
    }

    @Test
    @DisplayName("비밀번호가 틀리면 401 LOGIN_FAILED. 없는 아이디도 같은 응답이고 남은 횟수는 주지 않는다")
    void wrongPasswordAndUnknownIdLookTheSame() throws Exception {
        TestMember m = signUp();
        String unknown = uniqueLgnId();

        for (String id : List.of(m.lgnId(), unknown)) {
            mockMvc.perform(loginRequest(id, "Wrong123!", m.deviceId(), m.ip()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("LOGIN_FAILED"))
                    .andExpect(jsonPath("$.detail.remainCnt").doesNotExist());
        }
        assertThat(history(m.lgnId())).containsExactly(Map.of("succ_yn", "N", "add_auth_yn", "N"));
        assertThat(history(unknown)).containsExactly(Map.of("succ_yn", "N", "add_auth_yn", "N"));
    }

    @Test
    @DisplayName("5회 연속 틀리면 5회째부터 423 LOGIN_LOCKED, 30분 동안 맞는 비밀번호도 막고 30분 정각에 풀린다 (고정 Clock)")
    void lockAfterFiveFailuresForThirtyMinutes() throws Exception {
        TestMember m = signUp();
        LocalDateTime t = LocalDateTime.of(2026, 11, 13, 9, 0, 0);

        for (int i = 1; i <= 4; i++) {
            testClock.set(t.plusSeconds(i));
            mockMvc.perform(loginRequest(m.lgnId(), "Wrong123!", m.deviceId(), m.ip()))
                    .andExpect(status().isUnauthorized());
        }
        LocalDateTime fifth = t.plusSeconds(5);
        testClock.set(fifth);
        mockMvc.perform(loginRequest(m.lgnId(), "Wrong123!", m.deviceId(), m.ip()))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("LOGIN_LOCKED"));

        testClock.set(fifth.plusMinutes(30).minusSeconds(1));
        mockMvc.perform(loginRequest(m.lgnId(), TEST_PASSWORD, m.deviceId(), m.ip()))
                .andExpect(status().isLocked());

        testClock.set(fifth.plusMinutes(30));
        mockMvc.perform(loginRequest(m.lgnId(), TEST_PASSWORD, m.deviceId(), m.ip()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("성공하면 연속 실패가 끊긴다: 4회 실패 → 성공 → 4회 실패는 잠기지 않는다")
    void successResetsConsecutiveFailures() throws Exception {
        TestMember m = signUp();
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(loginRequest(m.lgnId(), "Wrong123!", m.deviceId(), m.ip())).andExpect(status().isUnauthorized());
        }
        login(m);
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(loginRequest(m.lgnId(), "Wrong123!", m.deviceId(), m.ip())).andExpect(status().isUnauthorized());
        }
        login(m);
    }

    @Test
    @DisplayName("없는 아이디도 5회째에 423이다(응답으로 아이디가 있는지 알 수 없다)")
    void unknownIdAlsoLocks() throws Exception {
        String unknown = uniqueLgnId();
        String device = newDeviceId();
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(loginRequest(unknown, "Wrong123!", device, DEFAULT_IP)).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(loginRequest(unknown, "Wrong123!", device, DEFAULT_IP)).andExpect(status().isLocked());
    }

    @Test
    @DisplayName("X-Device-Id가 없거나 UUID 형식이 아니면 400이고 이력도 남기지 않는다")
    void deviceIdRequired() throws Exception {
        TestMember m = signUp();
        mockMvc.perform(loginRequest(m.lgnId(), TEST_PASSWORD, null, m.ip())).andExpect(status().isBadRequest());
        mockMvc.perform(loginRequest(m.lgnId(), TEST_PASSWORD, "abc", m.ip())).andExpect(status().isBadRequest());
        assertThat(history(m.lgnId())).isEmpty();
    }

    @Test
    @DisplayName("새 기기면 202(토큰 없음, 가린 이메일)·탐지 PENDING, 코드가 맞으면 200 토큰·탐지 PASS·EMAIL_AUTH 등록, 다음 로그인은 바로 200")
    void newDeviceRequiresEmailAuth() throws Exception {
        TestMember m = signUp();
        String newDevice = newDeviceId();

        MvcResult accepted = mockMvc.perform(loginRequest(m.lgnId(), TEST_PASSWORD, newDevice, m.ip()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.authRequired").value(true))
                .andExpect(jsonPath("$.authId").isNotEmpty())
                .andExpect(jsonPath("$.mskEmlAddr").value(m.emlAddr().substring(0, 2) + "***@test.stockshield.dev"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andReturn();
        String authId = body(accepted).path("authId").asText();
        assertThat(detections(m.mbrNo())).containsExactly("PENDING");
        assertThat(history(m.lgnId())).containsExactly(Map.of("succ_yn", "Y", "add_auth_yn", "Y"));

        mockMvc.perform(verifyRequest(authId, fakeMail.lastCodeTo(m.emlAddr())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.roleCd").value("ROLE_USER"));
        assertThat(detections(m.mbrNo())).containsExactly("PASS");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reg_dv_cd FROM trusted_access WHERE mbr_no = ? AND dvc_id = ?", String.class, m.mbrNo(), newDevice))
                .isEqualTo("EMAIL_AUTH");

        // 코드는 1회용
        mockMvc.perform(verifyRequest(authId, fakeMail.lastCodeTo(m.emlAddr()))).andExpect(status().isGone());
        mockMvc.perform(loginRequest(m.lgnId(), TEST_PASSWORD, newDevice, m.ip())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("아는 기기라도 처음 보는 IP면 새 접속이다")
    void newIpIsNewAccess() throws Exception {
        TestMember m = signUp();
        mockMvc.perform(loginRequest(m.lgnId(), TEST_PASSWORD, m.deviceId(), CAFE_IP))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("인증 코드를 틀리면 400(남은 횟수 4→1), 5회째 410이고 탐지 기록은 FAIL, 그 뒤 맞는 코드도 410")
    void fiveWrongCodesFailDetection() throws Exception {
        TestMember m = signUp();
        String authId = newAccessAuthId(m);
        String code = fakeMail.lastCodeTo(m.emlAddr());
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int remain = 4; remain >= 1; remain--) {
            mockMvc.perform(verifyRequest(authId, wrong))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("AUTH_CODE_MISMATCH"))
                    .andExpect(jsonPath("$.detail.remainCnt").value(remain));
        }
        mockMvc.perform(verifyRequest(authId, wrong))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("AUTH_CODE_EXPIRED"));
        assertThat(detections(m.mbrNo())).containsExactly("FAIL");

        mockMvc.perform(verifyRequest(authId, code)).andExpect(status().isGone());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trusted_access WHERE mbr_no = ?", Integer.class,
                m.mbrNo())).isEqualTo(1);
    }

    @Test
    @DisplayName("코드 형식이 틀리면(숫자 6자리 아님) 400 INVALID_REQUEST이고 오류 횟수에 넣지 않는다")
    void malformedCodeIsNotCounted() throws Exception {
        TestMember m = signUp();
        String authId = newAccessAuthId(m);
        mockMvc.perform(verifyRequest(authId, "12a456")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        String wrong = fakeMail.lastCodeTo(m.emlAddr()).equals("000000") ? "111111" : "000000";
        mockMvc.perform(verifyRequest(authId, wrong)).andExpect(jsonPath("$.detail.remainCnt").value(4));
    }

    @Test
    @DisplayName("새 접속 인증은 첫 로그인부터 5분: 4분 59초에는 통과, 5분 정각에는 410 (FDS의 expiresAt과 같은 시각, 고정 Clock)")
    void newAccessValidForFiveMinutes() throws Exception {
        LocalDateTime t = LocalDateTime.of(2026, 11, 13, 13, 0, 0);
        TestMember inTime = signUp();
        TestMember late = signUp();

        testClock.set(t);
        String inTimeAuth = newAccessAuthId(inTime);
        String lateAuth = newAccessAuthId(late);

        testClock.set(t.plusMinutes(5).minusSeconds(1));
        mockMvc.perform(verifyRequest(inTimeAuth, fakeMail.lastCodeTo(inTime.emlAddr()))).andExpect(status().isOk());

        testClock.set(t.plusMinutes(5));
        mockMvc.perform(verifyRequest(lateAuth, fakeMail.lastCodeTo(late.emlAddr())))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("AUTH_CODE_EXPIRED"));
    }

    @Test
    @DisplayName("재발송: 60초 안 429, 60초 뒤 200·ttlSec는 남은 초(240), 이전 코드는 무효, 새 코드로 통과")
    void resendKeepsExpiryAndInvalidatesPreviousCode() throws Exception {
        LocalDateTime t = LocalDateTime.of(2026, 11, 13, 14, 0, 0);
        TestMember m = signUp();
        testClock.set(t);
        String authId = newAccessAuthId(m);
        String firstCode = fakeMail.lastCodeTo(m.emlAddr());

        testClock.set(t.plusSeconds(59));
        mockMvc.perform(resendRequest(authId))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));

        testClock.set(t.plusSeconds(60));
        mockMvc.perform(resendRequest(authId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ttlSec").value(240));
        String secondCode = fakeMail.lastCodeTo(m.emlAddr());

        if (!secondCode.equals(firstCode)) {
            mockMvc.perform(verifyRequest(authId, firstCode)).andExpect(status().isBadRequest());
        }
        mockMvc.perform(verifyRequest(authId, secondCode)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("재발송: 만료됐거나 없는 authId, 가입용 authId는 410")
    void resendOfDeadAuthIdIsGone() throws Exception {
        LocalDateTime t = LocalDateTime.of(2026, 11, 13, 15, 0, 0);
        TestMember m = signUp();
        testClock.set(t);
        String authId = newAccessAuthId(m);

        testClock.set(t.plusMinutes(5));
        mockMvc.perform(resendRequest(authId)).andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("AUTH_CODE_EXPIRED"));
        mockMvc.perform(resendRequest("no-such-auth-id")).andExpect(status().isGone());

        String signupAuthId = requestSignupCode(emailOf(uniqueLgnId()));
        mockMvc.perform(resendRequest(signupAuthId)).andExpect(status().isGone());
    }

    @Test
    @DisplayName("가입용 코드로는 새 접속 인증을 할 수 없다")
    void signupCodeCannotVerifyLogin() throws Exception {
        String email = emailOf(uniqueLgnId());
        String signupAuthId = requestSignupCode(email);
        mockMvc.perform(verifyRequest(signupAuthId, fakeMail.lastCodeTo(email))).andExpect(status().isGone());
    }

    @Test
    @DisplayName("메일을 보낼 수 없으면 새 접속은 503 AUTH_UNAVAILABLE·탐지 FAIL이고, 아는 기기 로그인은 그대로 된다")
    void mailOutageBlocksOnlyNewAccess() throws Exception {
        TestMember m = signUp();
        fakeMail.setFailing(true);

        mockMvc.perform(loginRequest(m.lgnId(), TEST_PASSWORD, newDeviceId(), m.ip()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_UNAVAILABLE"));
        assertThat(detections(m.mbrNo())).containsExactly("FAIL");

        login(m);
    }

    // ------------------------------------------------------------------ 도우미

    /** 새 기기로 로그인해 202의 authId를 받는다 */
    private String newAccessAuthId(TestMember m) throws Exception {
        MvcResult r = mockMvc.perform(loginRequest(m.lgnId(), TEST_PASSWORD, newDeviceId(), m.ip()))
                .andExpect(status().isAccepted())
                .andReturn();
        return body(r).path("authId").asText();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder resendRequest(String authId)
            throws Exception {
        return post("/api/v1/auth/email-resend").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("authId", authId)));
    }

    private List<Map<String, Object>> history(String lgnId) {
        return jdbcTemplate.queryForList(
                "SELECT succ_yn, add_auth_yn FROM login_history WHERE lgn_id = ? ORDER BY lgn_hist_seq", lgnId);
    }

    private List<String> detections(long mbrNo) {
        return jdbcTemplate.queryForList(
                "SELECT rslt_cd FROM fds_detection WHERE mbr_no = ? AND rule_cd = 'LOGIN_NEW_ACCESS' ORDER BY dtc_seq",
                String.class, mbrNo);
    }
}
