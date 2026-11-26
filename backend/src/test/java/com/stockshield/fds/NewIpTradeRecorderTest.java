package com.stockshield.fds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.stockshield.fds.api.OrderFilledEvent;
import com.stockshield.fds.trade.NewIpTradeRecorder;
import com.stockshield.fds.trade.NewIpTradeRecorder.Reason;
import com.stockshield.support.IntegrationTestSupport;

/**
 * FDS ③ 새 IP 직후 거래 기록을 실제 PostgreSQL(앱 계정 권한)에서 확인한다.
 * FDS_TRD_001 #1·#2·#4, 연결 합의서 v0.0.8 7절(15-1·15-2·15-3), 부록 #18
 * 테스트 메서드에 @Transactional을 붙이지 않는다(커밋 뒤 리스너가 실행되어야 한다).
 */
class NewIpTradeRecorderTest extends IntegrationTestSupport {

    static final String HOME_IP = "198.51.100.10";
    static final String NEW_IP = "198.51.100.200";

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate tx;
    @Autowired NewIpTradeRecorder recorder;
    @Autowired ApplicationEventPublisher events;

    final LocalDateTime base = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).minusDays(2);

    @Test
    @DisplayName("이메일 인증으로 등록한 IP의 첫 인증 23시간 59분 59초 뒤 주문은 NEW_IP_AFTER_AUTH, 24시간이면 기록하지 않는다")
    void windowBoundary() {
        Acct a = account();
        trusted(a.mbrNo, NEW_IP, "dvc-1", "EMAIL_AUTH", base);
        trusted(a.mbrNo, NEW_IP, "dvc-2", "EMAIL_AUTH", base.plusHours(10));   // 같은 IP의 뒤 행은 첫 인증 시각을 바꾸지 않는다

        String inside = filledOrder(a, NEW_IP, base.plusHours(24).minusSeconds(1));
        String outside = filledOrder(a, NEW_IP, base.plusHours(24));
        assertThat(record(inside)).contains(Reason.NEW_IP_AFTER_AUTH);
        assertThat(record(outside)).isEmpty();

        Map<String, Object> d = detectionOf(inside);
        assertThat(d).containsEntry("rule_cd", "TRADE_NEW_IP").containsEntry("rslt_cd", "LOGGED")
                .containsEntry("acnt_no", a.acntNo).containsEntry("conn_ip", NEW_IP);
        assertThat(d.get("reason")).isEqualTo("NEW_IP_AFTER_AUTH");
        assertThat((String) d.get("first_auth")).startsWith(base.toString().substring(0, 16));
    }

    @Test
    @DisplayName("가입 때 등록한 IP는 다른 기기로 이메일 인증을 해도 새 IP로 보지 않는다")
    void signupIpIsExcluded() {
        Acct a = account();
        trusted(a.mbrNo, HOME_IP, "dvc-1", "SIGNUP", base);
        trusted(a.mbrNo, HOME_IP, "dvc-2", "EMAIL_AUTH", base.plusHours(1));
        assertThat(record(filledOrder(a, HOME_IP, base.plusHours(2)))).isEmpty();
    }

    @Test
    @DisplayName("신뢰 목록에 없는 IP의 주문은 IP_NOT_TRUSTED로 기록하고, 첫 인증 시각은 없다")
    void untrustedIp() {
        Acct a = account();
        trusted(a.mbrNo, HOME_IP, "dvc-1", "SIGNUP", base);
        String ordNo = filledOrder(a, "203.0.113.50", base.plusMinutes(30));
        assertThat(record(ordNo)).contains(Reason.IP_NOT_TRUSTED);
        Map<String, Object> d = detectionOf(ordNo);
        assertThat(d.get("reason")).isEqualTo("IP_NOT_TRUSTED");
        assertThat(d.get("first_auth")).isNull();
    }

    @Test
    @DisplayName("주문 뒤에 등록된 IP는 주문 시점에 신뢰 목록에 없었으므로 IP_NOT_TRUSTED")
    void ipRegisteredAfterOrder() {
        Acct a = account();
        String ordNo = filledOrder(a, NEW_IP, base);
        trusted(a.mbrNo, NEW_IP, "dvc-1", "EMAIL_AUTH", base.plusMinutes(1));
        assertThat(record(ordNo)).contains(Reason.IP_NOT_TRUSTED);
    }

    @Test
    @DisplayName("거절 주문은 기록하지 않고, 같은 주문번호는 한 번만 기록한다")
    void rejectedAndDuplicate() {
        Acct a = account();
        assertThat(record(rejectedOrder(a, NEW_IP, base))).isEmpty();

        String ordNo = filledOrder(a, NEW_IP, base);
        assertThat(record(ordNo)).isPresent();
        assertThat(record(ordNo)).isEmpty();
        assertThat(countOf(ordNo)).isEqualTo(1);
    }

    @Test
    @DisplayName("체결 주문 트랜잭션에서 OrderFilledEvent를 발행하면 커밋 뒤 별도 스레드에서 기록된다")
    void recordedAfterCommitViaEvent() {
        Acct a = account();
        trusted(a.mbrNo, NEW_IP, "dvc-1", "EMAIL_AUTH", LocalDateTime.now().minusHours(1));
        String ordNo = tx.execute(s -> {
            String no = filledOrder(a, NEW_IP, LocalDateTime.now());
            events.publishEvent(new OrderFilledEvent(no));
            return no;
        });
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(countOf(ordNo)).isEqualTo(1));
    }

    @Test
    @DisplayName("주문 트랜잭션이 롤백되면 이벤트를 발행했어도 기록하지 않는다")
    void notRecordedWhenOrderRolledBack() {
        Acct a = account();
        String ordNo = "O" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        tx.executeWithoutResult(s -> {
            events.publishEvent(new OrderFilledEvent(ordNo));
            s.setRollbackOnly();
        });
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(countOf(ordNo)).isZero());
    }

    // ------------------------------------------------------------------ 도우미

    private Optional<Reason> record(String ordNo) {
        return tx.execute(s -> recorder.record(ordNo));
    }

    record Acct(long mbrNo, String acntNo) {
    }

    private Acct account() {
        String lgnId = "t" + UUID.randomUUID().toString().substring(0, 8);
        long mbrNo = jdbcTemplate.queryForObject(
                "INSERT INTO member (lgn_id, lgn_pwd, mbr_nm, eml_addr) VALUES (?, 'hash', '테스트', 'test@example.com') "
                        + "RETURNING mbr_no", Long.class, lgnId);
        long seq = jdbcTemplate.queryForObject("SELECT nextval('acnt_no_seq')", Long.class);
        String acntNo = "100" + String.format("%08d", seq) + "0";
        jdbcTemplate.update("INSERT INTO account (acnt_no, mbr_no, init_pkg_cd, init_dps_amt, dps_bal, wdr_pwd) "
                + "VALUES (?, ?, 'PKG_50M', 50000000, 50000000, 'hash')", acntNo, mbrNo);
        return new Acct(mbrNo, acntNo);
    }

    private void trusted(long mbrNo, String ip, String dvc, String path, LocalDateTime regTs) {
        jdbcTemplate.update("INSERT INTO trusted_access (mbr_no, conn_ip, dvc_id, user_agt, reg_dv_cd, reg_ts, last_use_ts) "
                + "VALUES (?, ?, ?, '-', ?, ?, ?)", mbrNo, ip, dvc, path, regTs, regTs);
    }

    private String filledOrder(Acct a, String ip, LocalDateTime regTs) {
        String ordNo = order(a, ip, regTs, true);
        jdbcTemplate.update("INSERT INTO trade_history (ord_no, acnt_no, tck_cd, trd_dv_cd, trd_qty, trd_prc, trd_amt, "
                + "post_dps_bal, post_hld_qty, reg_ts) VALUES (?, ?, '005930', 'SELL', 10, 214500, 2145000, 0, 0, ?)",
                ordNo, a.acntNo, regTs);
        return ordNo;
    }

    private String rejectedOrder(Acct a, String ip, LocalDateTime regTs) {
        return order(a, ip, regTs, false);
    }

    private String order(Acct a, String ip, LocalDateTime regTs, boolean filled) {
        jdbcTemplate.update("INSERT INTO stock_master (tck_cd, tck_nm, mkt_cd) VALUES ('005930', '삼성전자', 'KOSPI') "
                + "ON CONFLICT (tck_cd) DO NOTHING");
        String ordNo = "O" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        jdbcTemplate.update("INSERT INTO orders (ord_no, acnt_no, tck_cd, ord_dv_cd, ord_qty, ord_prc, prc_ts, "
                        + "ord_stat_cd, rjct_rsn_cd, idmp_key, conn_ip, dvc_id, user_agt, trc_id, reg_ts) "
                        + "VALUES (?, ?, '005930', 'SELL', 10, ?, ?, ?, ?, ?, ?, 'dvc-x', 'test-agent', ?, ?)",
                ordNo, a.acntNo, filled ? 214_500L : null, filled ? regTs : null, filled ? "FILLED" : "REJECTED",
                filled ? null : "INSUFFICIENT_HOLDING", UUID.randomUUID().toString(), ip,
                UUID.randomUUID().toString(), regTs);
        return ordNo;
    }

    private Map<String, Object> detectionOf(String ordNo) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT d.*, d.dtl_data ->> 'reason' AS reason, "
                + "d.dtl_data ->> 'ipFirstAuthTs' AS first_auth FROM fds_detection d WHERE ord_no = ?", ordNo);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private int countOf(String ordNo) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fds_detection WHERE ord_no = ? AND rule_cd = 'TRADE_NEW_IP'",
                Integer.class, ordNo);
    }
}
