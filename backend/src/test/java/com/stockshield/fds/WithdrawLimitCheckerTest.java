package com.stockshield.fds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockshield.fds.api.CheckResult;
import com.stockshield.fds.api.FdsService;
import com.stockshield.fds.api.LedgerReader;
import com.stockshield.fds.api.LockedAccount;
import com.stockshield.fds.api.RequestMeta;
import com.stockshield.fds.api.WithdrawCheckResult;
import com.stockshield.fds.api.WithdrawCheckResult.Decision;
import com.stockshield.fds.api.WithdrawCheckResult.LargeReason;
import com.stockshield.fds.api.WithdrawCommand;
import com.stockshield.fds.detection.DetectionChangedEvent;
import com.stockshield.fds.detection.DetectionRecorder;
import com.stockshield.fds.detection.DetectionResult;
import com.stockshield.fds.withdraw.WithdrawLimitChecker;
import com.stockshield.fds.withdraw.WithdrawRequestHash;
import com.stockshield.support.IntegrationTestSupport;

/**
 * FDS ② 대량 출금 판단·탐지 기록을 실제 PostgreSQL(앱 계정 권한)에서 확인한다.
 * FDS_WDR_001 #1~#3, FDS_LOG_001 #2·#4·#5, 연결 합의서 v0.0.8 6절·12절(경계값: 4,999,999/5,000,000원, 4/5회 오류, 5분 전후)
 *
 * 시각을 움직여야 하는 판단은 컨텍스트의 빈 대신 같은 DB 연결을 쓰는 인스턴스를 테스트 시계로 직접 만들어 확인한다.
 * 테스트 시계는 실제 현재 시각에서 시작해, 1분마다 도는 만료 정리 작업(실제 시계)과 어긋나지 않게 한다.
 * 테스트마다 새 회원·계좌와 새 멱등키를 만든다(앱 계정에는 DELETE 권한이 없다).
 */
@RecordApplicationEvents
class WithdrawLimitCheckerTest extends IntegrationTestSupport {

    static final long THRESHOLD = 5_000_000L;
    static final String PWD = "135790";

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate tx;
    @Autowired LedgerReader ledgerReader;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;
    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired FdsService fdsService;
    @Autowired ApplicationEvents events;

    TestClock clock;
    DetectionRecorder recorder;
    WithdrawLimitChecker checker;
    String pwdHash;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        recorder = new DetectionRecorder(jdbcTemplate, objectMapper, eventPublisher, clock);
        checker = new WithdrawLimitChecker(ledgerReader, passwordEncoder, recorder, clock, THRESHOLD);
        pwdHash = passwordEncoder.encode(PWD);
    }

    // ------------------------------------------------------------------ 대량 판단 (FDS_WDR_001 #1)

    @Test
    @DisplayName("1회 4,999,999원은 대량이 아니고, 5,000,000원은 SINGLE로 비밀번호를 요구한다")
    void singleAmountBoundary() {
        LockedAccount account = account(0);
        assertThat(check(account, cmd(4_999_999, null)).decision()).isEqualTo(Decision.NOT_LARGE);

        WithdrawCheckResult r = check(account, cmd(5_000_000, null));
        assertThat(r.decision()).isEqualTo(Decision.PASSWORD_REQUIRED);
        assertThat(r.reason()).isEqualTo(LargeReason.SINGLE);
        assertThat(r.threshold()).isEqualTo(THRESHOLD);
        assertThat(r.dtcSeq()).isNull();
    }

    @Test
    @DisplayName("24시간 DONE 합계 + 이번 금액이 기준 이상이면 DAILY_SUM, 기준 시각(24시간 전)의 출금도 더한다")
    void dailySumBoundary() {
        LockedAccount account = account(0);
        LocalDateTime now = clock.now();
        insertWithdrawal(account.acntNo(), 1_000_000, "DONE", now.minusHours(24));            // 경계: 포함
        insertWithdrawal(account.acntNo(), 2_000_000, "DONE", now.minusHours(1));
        insertWithdrawal(account.acntNo(), 9_000_000, "DONE", now.minusHours(24).minusSeconds(1)); // 24시간 밖
        insertWithdrawal(account.acntNo(), 4_000_000, "REJECTED", now.minusMinutes(1));        // 거절: 제외

        WithdrawCheckResult below = check(account, cmd(1_999_999, null));
        assertThat(below.decision()).isEqualTo(Decision.NOT_LARGE);
        assertThat(below.dailySum()).isEqualTo(3_000_000L);

        WithdrawCheckResult r = check(account, cmd(2_000_000, null));
        assertThat(r.decision()).isEqualTo(Decision.PASSWORD_REQUIRED);
        assertThat(r.reason()).isEqualTo(LargeReason.DAILY_SUM);
        assertThat(r.dailySum()).isEqualTo(3_000_000L);
    }

    @Test
    @DisplayName("대량이 아니면 보낸 비밀번호는 확인하지 않는다(틀려도 NOT_LARGE)")
    void smallWithdrawalIgnoresPassword() {
        LockedAccount account = account(0);
        WithdrawCheckResult r = check(account, cmd(100_000, "000000"));
        assertThat(r.decision()).isEqualTo(Decision.NOT_LARGE);
        assertThat(r.failCntAfter()).isNull();
    }

    // ------------------------------------------------------------------ 428 → 재요청 (FDS_WDR_001 #2, 합의 11·13)

    @Test
    @DisplayName("428이면 PENDING을 저장하고, 같은 키 + 맞는 비밀번호 재요청은 그 기록을 PASS + 출금번호로 바꾼다")
    void pendingThenPassed() {
        LockedAccount account = account(0);
        WithdrawCommand first = cmd(6_000_000, null);
        WithdrawCheckResult r1 = check(account, first);
        long dtcSeq = record(account, first, r1, null);

        Map<String, Object> row = detection(dtcSeq);
        assertThat(row.get("rslt_cd")).isEqualTo("PENDING");
        assertThat(row.get("rule_cd")).isEqualTo("WITHDRAW_LARGE");
        assertThat(row.get("req_idmp_key")).isEqualTo(first.idmpKey());
        assertThat(row.get("wdr_no")).isNull();
        assertThat(row.get("conn_ip")).isEqualTo("203.0.113.9");
        assertThat((String) row.get("req_hash")).isEqualTo(WithdrawRequestHash.of(first));

        clock.advance(Duration.ofMinutes(4));
        WithdrawCommand retry = withPwd(first, PWD);
        WithdrawCheckResult r2 = check(account, retry);
        assertThat(r2.decision()).isEqualTo(Decision.PASSED);
        assertThat(r2.dtcSeq()).isEqualTo(dtcSeq);

        String wdrNo = insertWithdrawal(account.acntNo(), 6_000_000, "DONE", clock.now());
        assertThat(record(account, retry, r2, wdrNo)).isEqualTo(dtcSeq);

        Map<String, Object> after = detection(dtcSeq);
        assertThat(after.get("rslt_cd")).isEqualTo("PASS");
        assertThat(after.get("wdr_no")).isEqualTo(wdrNo);
        assertThat(((Timestamp) after.get("mod_ts")).toLocalDateTime()).isEqualTo(clock.now());
        assertThat(((Timestamp) after.get("reg_ts")).toLocalDateTime()).isEqualTo(clock.now().minusMinutes(4));
    }

    @Test
    @DisplayName("428 뒤 같은 키로 금액을 바꾸면 CONTENT_CHANGED, 비밀번호 없이 그대로 다시 오면 기존 대기 기록으로 다시 428")
    void retryContentChangedOrStillWaiting() {
        LockedAccount account = account(0);
        WithdrawCommand first = cmd(5_000_000, null);
        long dtcSeq = record(account, first, check(account, first), null);

        WithdrawCommand changed = new WithdrawCommand(5_000_001, first.rcvBankNm(), first.rcvAcntNo(),
                first.idmpKey(), PWD, first.meta());
        WithdrawCheckResult c = check(account, changed);
        assertThat(c.decision()).isEqualTo(Decision.CONTENT_CHANGED);

        WithdrawCheckResult again = check(account, first);
        assertThat(again.decision()).isEqualTo(Decision.PASSWORD_REQUIRED);
        assertThat(again.dtcSeq()).isEqualTo(dtcSeq);
        assertThat(detection(dtcSeq).get("rslt_cd")).isEqualTo("PENDING");
        // 기존 대기 기록을 이어 쓰는 428에는 recordWithdrawal을 부르지 않는다(합의 13)
        assertThatThrownBy(() -> record(account, first, again, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("대기 기록은 4분 59초까지 유효하고, 정확히 5분이 되면 만료되어 FAIL로 닫고 새 요청으로 판단한다")
    void pendingExpiresAtFiveMinutes() {
        LockedAccount account = account(0);
        WithdrawCommand first = cmd(5_000_000, null);
        long dtcSeq = record(account, first, check(account, first), null);

        clock.advance(Duration.ofMinutes(5).minusSeconds(1));
        assertThat(check(account, first).dtcSeq()).isEqualTo(dtcSeq);

        clock.advance(Duration.ofSeconds(1));
        WithdrawCheckResult r = check(account, first);
        assertThat(r.decision()).isEqualTo(Decision.PASSWORD_REQUIRED);
        assertThat(r.dtcSeq()).isNull();
        assertThat(detection(dtcSeq).get("rslt_cd")).isEqualTo("FAIL");
    }

    // ------------------------------------------------------------------ 비밀번호 오류 (FDS_WDR_001 #3, 합의 12)

    @Test
    @DisplayName("오류 3회 계좌가 틀리면 MISMATCH(4), 4회 계좌가 틀리면 LOCKED(5). 처음부터 비밀번호가 있으면 결과로 바로 저장")
    void mismatchAndLockBoundary() {
        LockedAccount threeFails = account(3);
        WithdrawCommand wrong = cmd(7_000_000, "000000");
        WithdrawCheckResult m = check(threeFails, wrong);
        assertThat(m.decision()).isEqualTo(Decision.MISMATCH);
        assertThat(m.failCntAfter()).isEqualTo(4);
        String wdr1 = insertWithdrawal(threeFails.acntNo(), 7_000_000, "REJECTED", clock.now());
        long d1 = record(threeFails, wrong, m, wdr1);
        assertThat(detection(d1)).containsEntry("rslt_cd", "FAIL").containsEntry("wdr_no", wdr1);

        LockedAccount fourFails = account(4);
        WithdrawCommand wrong2 = cmd(7_000_000, "000000");
        WithdrawCheckResult l = check(fourFails, wrong2);
        assertThat(l.decision()).isEqualTo(Decision.LOCKED);
        assertThat(l.failCntAfter()).isEqualTo(5);
        String wdr2 = insertWithdrawal(fourFails.acntNo(), 7_000_000, "REJECTED", clock.now());
        long d2 = record(fourFails, wrong2, l, wdr2);
        assertThat(detection(d2)).containsEntry("rslt_cd", "LOCKED").containsEntry("wdr_no", wdr2);

        assertThat(events.stream(DetectionChangedEvent.class)
                .filter(e -> e.dtcSeq() == d2).findFirst()).hasValueSatisfying(e -> assertThat(e.urgent()).isTrue());
    }

    @Test
    @DisplayName("맞는 비밀번호는 오류 횟수와 관계없이 PASSED (오류 횟수 0으로 되돌리기는 원장이 한다)")
    void correctPasswordPasses() {
        LockedAccount account = account(4);
        WithdrawCheckResult r = check(account, cmd(5_000_000, PWD));
        assertThat(r.decision()).isEqualTo(Decision.PASSED);
        assertThat(r.failCntAfter()).isNull();
        assertThat(r.dtcSeq()).isNull();
    }

    // ------------------------------------------------------------------ 만료 정리·관리자 확인

    @Test
    @DisplayName("만료 정리는 5분이 지난 PENDING만 FAIL로 바꾸고 mod_ts를 Clock 시각으로 남긴다")
    void expirePendingOnlyOldOnes() {
        LockedAccount account = account(0);
        WithdrawCommand oldReq = cmd(5_000_000, null);
        long oldSeq = record(account, oldReq, check(account, oldReq), null);
        clock.advance(Duration.ofMinutes(3));
        WithdrawCommand newReq = cmd(5_000_000, null);
        long newSeq = record(account, newReq, check(account, newReq), null);

        clock.advance(Duration.ofMinutes(2));   // oldSeq는 정확히 5분, newSeq는 2분
        tx.executeWithoutResult(s -> recorder.expirePending());

        assertThat(detection(oldSeq).get("rslt_cd")).isEqualTo("FAIL");
        assertThat(((Timestamp) detection(oldSeq).get("mod_ts")).toLocalDateTime()).isEqualTo(clock.now());
        assertThat(detection(newSeq).get("rslt_cd")).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("관리자 확인은 한 번만 CHECKED, 다시 하면 ALREADY_CHECKED, 없는 번호는 NOT_FOUND")
    void markChecked() {
        LockedAccount account = account(0);
        WithdrawCommand req = cmd(5_000_000, null);
        long dtcSeq = record(account, req, check(account, req), null);

        CheckResult first = tx.execute(s -> fdsService.markChecked(dtcSeq));
        CheckResult second = tx.execute(s -> fdsService.markChecked(dtcSeq));
        CheckResult unknown = tx.execute(s -> fdsService.markChecked(Long.MAX_VALUE));
        assertThat(first).isEqualTo(CheckResult.CHECKED);
        assertThat(second).isEqualTo(CheckResult.ALREADY_CHECKED);
        assertThat(unknown).isEqualTo(CheckResult.NOT_FOUND);
        assertThat(detection(dtcSeq).get("chk_yn")).isEqualTo("Y");
    }

    @Test
    @DisplayName("FdsService의 출금 판단은 원장 트랜잭션 밖에서 부르면 실패한다(MANDATORY)")
    void checkWithdrawalRequiresCallerTransaction() {
        LockedAccount account = account(0);
        assertThatThrownBy(() -> fdsService.checkWithdrawal(account, cmd(100, null)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("컨텍스트의 FdsService 빈으로도 428 대기 기록이 저장된다(실제 구현이 등록되어 있음)")
    void beanRecordsPending() {
        LockedAccount account = account(0);
        WithdrawCommand req = cmd(5_000_000, null);
        Long dtcSeq = tx.execute(s -> {
            WithdrawCheckResult r = fdsService.checkWithdrawal(account, req);
            return fdsService.recordWithdrawal(account, req, r, null);
        });
        assertThat(detection(dtcSeq).get("rslt_cd")).isEqualTo("PENDING");
    }

    // ------------------------------------------------------------------ 도우미

    private WithdrawCheckResult check(LockedAccount account, WithdrawCommand cmd) {
        return tx.execute(s -> checker.check(account, cmd));
    }

    private long record(LockedAccount account, WithdrawCommand cmd, WithdrawCheckResult r, String wdrNo) {
        Long seq = tx.execute(s -> checker.record(account, cmd, r, wdrNo));
        return seq;
    }

    private static WithdrawCommand cmd(long amount, String pwd) {
        return new WithdrawCommand(amount, "테스트은행", "12345678901", UUID.randomUUID().toString(), pwd,
                new RequestMeta("203.0.113.9", "6f1c2d3e-0000-4000-8000-000000000009", "test-agent"));
    }

    private static WithdrawCommand withPwd(WithdrawCommand c, String pwd) {
        return new WithdrawCommand(c.wdrAmt(), c.rcvBankNm(), c.rcvAcntNo(), c.idmpKey(), pwd, c.meta());
    }

    private Map<String, Object> detection(long dtcSeq) {
        return jdbcTemplate.queryForMap("SELECT d.*, d.dtl_data ->> 'reqHash' AS req_hash FROM fds_detection d "
                + "WHERE dtc_seq = ?", dtcSeq);
    }

    private LockedAccount account(int failCnt) {
        String lgnId = "w" + UUID.randomUUID().toString().substring(0, 8);
        long mbrNo = jdbcTemplate.queryForObject(
                "INSERT INTO member (lgn_id, lgn_pwd, mbr_nm, eml_addr) VALUES (?, 'hash', '테스트', 'test@example.com') "
                        + "RETURNING mbr_no", Long.class, lgnId);
        long seq = jdbcTemplate.queryForObject("SELECT nextval('acnt_no_seq')", Long.class);
        String acntNo = "100" + String.format("%08d", seq) + "0";
        jdbcTemplate.update("INSERT INTO account (acnt_no, mbr_no, init_pkg_cd, init_dps_amt, dps_bal, wdr_pwd, "
                + "wdr_pwd_fail_cnt) VALUES (?, ?, 'PKG_50M', 50000000, 50000000, ?, ?)", acntNo, mbrNo, pwdHash, failCnt);
        return new LockedAccount(acntNo, mbrNo, pwdHash, failCnt);
    }

    private String insertWithdrawal(String acntNo, long amount, String status, LocalDateTime regTs) {
        boolean done = "DONE".equals(status);
        String wdrNo = "W" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        jdbcTemplate.update("INSERT INTO withdrawal (wdr_no, acnt_no, wdr_amt, rcv_bank_nm, rcv_acnt_no, wdr_stat_cd, "
                        + "rjct_rsn_cd, post_dps_bal, idmp_key, conn_ip, user_agt, trc_id, reg_ts) "
                        + "VALUES (?, ?, ?, '테스트은행', '12345678901', ?, ?, ?, ?, '127.0.0.1', '-', ?, ?)",
                wdrNo, acntNo, amount, status, done ? null : "WDR_PWD_MISMATCH", done ? 0L : null,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), regTs);
        return wdrNo;
    }
}
