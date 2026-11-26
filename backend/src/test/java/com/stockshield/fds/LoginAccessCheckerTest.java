package com.stockshield.fds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockshield.fds.access.LoginAccessChecker;
import com.stockshield.fds.access.TrustedAccessStore;
import com.stockshield.fds.api.ConfirmResult;
import com.stockshield.fds.api.FdsService;
import com.stockshield.fds.api.LoginAccessCommand;
import com.stockshield.fds.api.LoginAccessResult;
import com.stockshield.fds.api.LoginAccessResult.Type;
import com.stockshield.fds.api.RequestMeta;
import com.stockshield.fds.detection.DetectionRecorder;
import com.stockshield.support.IntegrationTestSupport;

/**
 * FDS ① 새 접속 판단·확정·실패와 가입 기기 등록을 실제 PostgreSQL(앱 계정 권한)에서 확인한다.
 * FDS_LGN_001 #1~#4, USR_AUTH_001 #4, FDS_LOG_001 #2, 연결 합의서 v0.0.8 5절(9-1·9-2·10), 12절(5분 전후 경계)
 */
class LoginAccessCheckerTest extends IntegrationTestSupport {

    static final String HOME_IP = "198.51.100.10";
    static final String CAFE_IP = "198.51.100.77";

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper objectMapper;
    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired FdsService fdsService;

    TestClock clock;
    LoginAccessChecker checker;
    String laptop;
    String phone;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        DetectionRecorder recorder = new DetectionRecorder(jdbcTemplate, objectMapper, eventPublisher, clock);
        checker = new LoginAccessChecker(new TrustedAccessStore(jdbcTemplate, clock), recorder);
        laptop = UUID.randomUUID().toString();
        phone = UUID.randomUUID().toString();
    }

    @Test
    @DisplayName("가입한 IP·기기로 다시 로그인하면 KNOWN이고 그 행의 마지막 사용 시각만 바뀐다")
    void signupDeviceIsKnown() {
        long mbrNo = member();
        signup(mbrNo, HOME_IP, laptop);
        Map<String, Object> before = trusted(mbrNo).get(0);
        assertThat(before.get("reg_dv_cd")).isEqualTo("SIGNUP");

        clock.advance(Duration.ofHours(3));
        LoginAccessResult r = check(mbrNo, HOME_IP, laptop, "Chrome/131");
        assertThat(r.type()).isEqualTo(Type.KNOWN);
        assertThat(r.dtcSeq()).isNull();

        Map<String, Object> after = trusted(mbrNo).get(0);
        assertThat(ts(after.get("last_use_ts"))).isEqualTo(clock.now());
        assertThat(after.get("reg_ts")).isEqualTo(before.get("reg_ts"));
        assertThat(detections(mbrNo)).isEmpty();
    }

    @Test
    @DisplayName("아는 IP에 새 기기면 NEW_ACCESS: PENDING 탐지 기록(계좌 없음)과 만료 시각 = 저장 시각 + 5분")
    void newDeviceIsNewAccess() {
        long mbrNo = member();
        signup(mbrNo, HOME_IP, laptop);

        LoginAccessResult r = check(mbrNo, HOME_IP, phone, "Safari/18");
        assertThat(r.type()).isEqualTo(Type.NEW_ACCESS);
        assertThat(r.expiresAt()).isEqualTo(clock.now().plusMinutes(5));

        Map<String, Object> d = detection(r.dtcSeq());
        assertThat(d).containsEntry("rule_cd", "LOGIN_NEW_ACCESS").containsEntry("rslt_cd", "PENDING")
                .containsEntry("conn_ip", HOME_IP).containsEntry("dvc_id", phone).containsEntry("user_agt", "Safari/18");
        assertThat(d.get("acnt_no")).isNull();
        assertThat(d.get("new_ip")).isEqualTo("false");
        assertThat(d.get("new_device")).isEqualTo("true");
    }

    @Test
    @DisplayName("아는 기기라도 처음 보는 IP면 NEW_ACCESS")
    void newIpIsNewAccess() {
        long mbrNo = member();
        signup(mbrNo, HOME_IP, laptop);
        assertThat(check(mbrNo, CAFE_IP, laptop, "Chrome/131").type()).isEqualTo(Type.NEW_ACCESS);
    }

    @Test
    @DisplayName("IP와 기기를 각각 찾으므로, 서로 다른 행에 있어도 둘 다 알면 KNOWN (같은 행이 없으면 갱신 없음)")
    void ipAndDeviceFoundInDifferentRows() {
        long mbrNo = member();
        signup(mbrNo, HOME_IP, laptop);
        LoginAccessResult cafePhone = check(mbrNo, CAFE_IP, phone, "Safari/18");
        assertThat(confirm(cafePhone.dtcSeq())).isEqualTo(ConfirmResult.CONFIRMED);

        clock.advance(Duration.ofMinutes(10));
        assertThat(check(mbrNo, HOME_IP, phone, "Safari/18").type()).isEqualTo(Type.KNOWN);
        assertThat(trusted(mbrNo)).allSatisfy(row -> assertThat(ts(row.get("last_use_ts"))).isBefore(clock.now()));
    }

    @Test
    @DisplayName("인증 성공이면 PASS로 바꾸고 로그인 때의 IP·기기·User-Agent를 EMAIL_AUTH로 등록, 다음 로그인은 KNOWN")
    void confirmRegistersTrustedAccess() {
        long mbrNo = member();
        signup(mbrNo, HOME_IP, laptop);
        LoginAccessResult r = check(mbrNo, CAFE_IP, phone, "Safari/18");

        clock.advance(Duration.ofMinutes(2));
        assertThat(confirm(r.dtcSeq())).isEqualTo(ConfirmResult.CONFIRMED);

        Map<String, Object> d = detection(r.dtcSeq());
        assertThat(d.get("rslt_cd")).isEqualTo("PASS");
        assertThat(ts(d.get("mod_ts"))).isEqualTo(clock.now());
        Map<String, Object> row = trusted(mbrNo).stream()
                .filter(t -> "EMAIL_AUTH".equals(t.get("reg_dv_cd"))).findFirst().orElseThrow();
        assertThat(row).containsEntry("conn_ip", CAFE_IP).containsEntry("dvc_id", phone).containsEntry("user_agt", "Safari/18");
        assertThat(ts(row.get("reg_ts"))).isEqualTo(clock.now());

        assertThat(check(mbrNo, CAFE_IP, phone, "Safari/18").type()).isEqualTo(Type.KNOWN);
    }

    @Test
    @DisplayName("인증 확인은 4분 59초까지 CONFIRMED, 정확히 5분이면 EXPIRED이고 신뢰 접속을 등록하지 않는다")
    void confirmBoundaryAtFiveMinutes() {
        long mbrNo = member();
        LoginAccessResult ok = check(mbrNo, HOME_IP, laptop, "-");
        clock.advance(Duration.ofMinutes(5).minusSeconds(1));
        assertThat(confirm(ok.dtcSeq())).isEqualTo(ConfirmResult.CONFIRMED);

        long other = member();
        LoginAccessResult late = check(other, HOME_IP, laptop, "-");
        clock.advance(Duration.ofMinutes(5));
        assertThat(confirm(late.dtcSeq())).isEqualTo(ConfirmResult.EXPIRED);
        assertThat(detection(late.dtcSeq()).get("rslt_cd")).isEqualTo("PENDING");   // FAIL 확정은 만료 정리가 한다
        assertThat(trusted(other)).isEmpty();
    }

    @Test
    @DisplayName("이미 PASS인 기록을 다시 확인하면 EXPIRED, 실패 기록 뒤의 확인도 EXPIRED")
    void confirmOnlyOnce() {
        long mbrNo = member();
        LoginAccessResult r = check(mbrNo, HOME_IP, laptop, "-");
        assertThat(confirm(r.dtcSeq())).isEqualTo(ConfirmResult.CONFIRMED);
        assertThat(confirm(r.dtcSeq())).isEqualTo(ConfirmResult.EXPIRED);

        LoginAccessResult failed = check(member(), HOME_IP, laptop, "-");
        tx.executeWithoutResult(s -> checker.fail(failed.dtcSeq()));
        assertThat(detection(failed.dtcSeq()).get("rslt_cd")).isEqualTo("FAIL");
        tx.executeWithoutResult(s -> checker.fail(failed.dtcSeq()));    // 이미 PENDING이 아니면 아무것도 하지 않는다
        assertThat(confirm(failed.dtcSeq())).isEqualTo(ConfirmResult.EXPIRED);
    }

    @Test
    @DisplayName("같은 기기로 새 접속 로그인을 두 번 하고 둘 다 확인해도 신뢰 접속은 한 행이고 둘 다 CONFIRMED")
    void sameDeviceConfirmedTwice() {
        long mbrNo = member();
        LoginAccessResult a = check(mbrNo, CAFE_IP, phone, "Safari/18");
        LoginAccessResult b = check(mbrNo, CAFE_IP, phone, "Safari/18");
        assertThat(confirm(a.dtcSeq())).isEqualTo(ConfirmResult.CONFIRMED);
        clock.advance(Duration.ofSeconds(30));
        assertThat(confirm(b.dtcSeq())).isEqualTo(ConfirmResult.CONFIRMED);

        List<Map<String, Object>> rows = trusted(mbrNo);
        assertThat(rows).hasSize(1);
        assertThat(ts(rows.get(0).get("last_use_ts"))).isEqualTo(clock.now());
    }

    @Test
    @DisplayName("FdsService 빈: 새 접속 판단은 트랜잭션 없이 불러도 커밋되어 돌아오고(REQUIRES_NEW), 가입 등록은 트랜잭션 밖이면 실패(MANDATORY)")
    void beanPropagation() {
        long mbrNo = member();
        LoginAccessResult r = fdsService.checkLoginAccess(
                new LoginAccessCommand(mbrNo, new RequestMeta(HOME_IP, laptop, "-")));
        assertThat(r.type()).isEqualTo(Type.NEW_ACCESS);
        assertThat(detection(r.dtcSeq()).get("rslt_cd")).isEqualTo("PENDING");

        assertThatThrownBy(() -> fdsService.registerSignupAccess(mbrNo, new RequestMeta(HOME_IP, laptop, "-")))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ------------------------------------------------------------------ 도우미

    private LoginAccessResult check(long mbrNo, String ip, String dvc, String ua) {
        return tx.execute(s -> checker.check(new LoginAccessCommand(mbrNo, new RequestMeta(ip, dvc, ua))));
    }

    private ConfirmResult confirm(long dtcSeq) {
        return tx.execute(s -> checker.confirm(dtcSeq));
    }

    private void signup(long mbrNo, String ip, String dvc) {
        tx.executeWithoutResult(s -> checker.registerSignup(mbrNo, new RequestMeta(ip, dvc, "Chrome/130")));
    }

    private long member() {
        String lgnId = "l" + UUID.randomUUID().toString().substring(0, 8);
        return jdbcTemplate.queryForObject(
                "INSERT INTO member (lgn_id, lgn_pwd, mbr_nm, eml_addr) VALUES (?, 'hash', '테스트', 'test@example.com') "
                        + "RETURNING mbr_no", Long.class, lgnId);
    }

    private List<Map<String, Object>> trusted(long mbrNo) {
        return jdbcTemplate.queryForList("SELECT * FROM trusted_access WHERE mbr_no = ? ORDER BY trst_seq", mbrNo);
    }

    private List<Map<String, Object>> detections(long mbrNo) {
        return jdbcTemplate.queryForList("SELECT * FROM fds_detection WHERE mbr_no = ?", mbrNo);
    }

    private Map<String, Object> detection(long dtcSeq) {
        return jdbcTemplate.queryForMap("SELECT d.*, d.dtl_data ->> 'newIp' AS new_ip, "
                + "d.dtl_data ->> 'newDevice' AS new_device FROM fds_detection d WHERE dtc_seq = ?", dtcSeq);
    }

    private static LocalDateTime ts(Object value) {
        return ((Timestamp) value).toLocalDateTime();
    }
}
