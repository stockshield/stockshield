package com.stockshield.common.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.stockshield.support.IntegrationTestSupport;
import com.stockshield.support.SqlStates;

/**
 * 애플리케이션이 쓰는 앱 DB 계정의 권한을 실제 PostgreSQL에서 확인한다.
 * 애플리케이션 버그나 실수가 있어도 거래 기록을 고치거나 지울 수 없고, 잘못된 값은 DB가 막는다.
 *
 * TRD_LED_001 테스트 (5) 앱 DB 계정으로 거래 내역 UPDATE·DELETE 시 거부됨
 * SYS_SEC_001 #3 감사 기록은 INSERT만, fds_detection은 정해진 컬럼만 갱신
 * 테이블 정의서 공통 규칙 'INSERT 전용'·'부분 갱신'·'코드 CHECK', 요구사항 부록 #7·#21
 */
class AppDbPermissionTest extends IntegrationTestSupport {

    /** 앱 계정으로 접속한 JdbcTemplate (spring.datasource) */
    @Autowired
    JdbcTemplate jdbcTemplate;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "orders", "trade_history", "withdrawal", "login_history", "fds_action_log", "reconcile_result"})
    @DisplayName("감사 테이블은 앱 계정으로 수정·삭제할 수 없다")
    void auditTablesAreInsertOnly(String table) {
        assertRejected("UPDATE " + table + " SET reg_ts = reg_ts WHERE 1 = 0", SqlStates.INSUFFICIENT_PRIVILEGE);
        assertRejected("DELETE FROM " + table + " WHERE 1 = 0", SqlStates.INSUFFICIENT_PRIVILEGE);
    }

    @Test
    @DisplayName("탐지 기록은 결과·출금번호·AI 설명·확인 여부만 바꿀 수 있고 삭제할 수 없다")
    void detectionAllowsOnlyListedColumns() {
        // 허용된 컬럼은 갱신된다 (0건이어도 권한 검사는 통과해야 한다)
        int updated = jdbcTemplate.update(
                "UPDATE fds_detection SET rslt_cd = 'PASS', wdr_no = NULL, ai_rsn_cont = NULL, ai_chk_cont = NULL, "
                        + "ai_stat_cd = 'DONE', chk_yn = 'Y', mod_ts = now() WHERE 1 = 0");
        assertThat(updated).isZero();

        // 처음 저장한 사실은 바꿀 수 없다
        assertRejected("UPDATE fds_detection SET dtl_data = '{}'::jsonb WHERE 1 = 0", SqlStates.INSUFFICIENT_PRIVILEGE);
        assertRejected("UPDATE fds_detection SET conn_ip = '0.0.0.0' WHERE 1 = 0", SqlStates.INSUFFICIENT_PRIVILEGE);
        assertRejected("DELETE FROM fds_detection WHERE 1 = 0", SqlStates.INSUFFICIENT_PRIVILEGE);
    }

    @Test
    @DisplayName("앱 계정은 테이블 구조를 바꾸거나 지울 수 없다")
    void appAccountCannotChangeSchema() {
        assertRejected("ALTER TABLE account ADD COLUMN memo VARCHAR(10)", SqlStates.INSUFFICIENT_PRIVILEGE);
        assertRejected("DROP TABLE orders", SqlStates.INSUFFICIENT_PRIVILEGE);
    }

    @Test
    @DisplayName("예수금은 애플리케이션이 잘못 계산해도 DB 제약으로 음수가 될 수 없다")
    void depositBalanceCannotBeNegative() {
        long mbrNo = insertMember();

        assertRejected("INSERT INTO account (acnt_no, mbr_no, init_pkg_cd, init_dps_amt, dps_bal, wdr_pwd) "
                + "VALUES ('" + newAccountNo() + "', " + mbrNo + ", 'PKG_10M', 10000000, -1, 'hash')",
                SqlStates.CHECK_VIOLATION);
    }

    @Test
    @DisplayName("코드 정의 시트에 없는 코드값은 DB가 거부한다")
    void undefinedCodeValueIsRejected() {
        String lgnId = "u" + UUID.randomUUID().toString().substring(0, 8);

        assertRejected("INSERT INTO member (lgn_id, lgn_pwd, mbr_nm, eml_addr, role_cd) "
                + "VALUES ('" + lgnId + "', 'hash', '테스트', 'test@example.com', 'ROLE_ROOT')",
                SqlStates.CHECK_VIOLATION);
    }

    @Test
    @DisplayName("가격이 없거나 0원인 체결 주문은 DB가 거부한다 (테이블 정의서 orders.ord_prc의 CHECK를 NULL까지 막도록 보완)")
    void filledOrderRequiresPositivePrice() {
        String acntNo = newAccountNo();
        jdbcTemplate.update("INSERT INTO account (acnt_no, mbr_no, init_pkg_cd, init_dps_amt, dps_bal, wdr_pwd) "
                + "VALUES (?, ?, 'PKG_10M', 10000000, 10000000, 'hash')", acntNo, insertMember());
        jdbcTemplate.update("INSERT INTO stock_master (tck_cd, tck_nm, mkt_cd) VALUES ('005930', '삼성전자', 'KOSPI') "
                + "ON CONFLICT (tck_cd) DO NOTHING");

        String insertFilled = "INSERT INTO orders (ord_no, acnt_no, tck_cd, ord_dv_cd, ord_qty, ord_prc, prc_ts, "
                + "ord_stat_cd, idmp_key, conn_ip, user_agt, trc_id) "
                + "VALUES ('%s', '" + acntNo + "', '005930', 'BUY', 1, %s, %s, 'FILLED', '%s', '127.0.0.1', '-', 'trace')";

        assertRejected(String.format(insertFilled, "O-null-" + acntNo, "NULL", "NULL", UUID.randomUUID()),
                SqlStates.CHECK_VIOLATION);
        assertRejected(String.format(insertFilled, "O-zero-" + acntNo, "0", "now()", UUID.randomUUID()),
                SqlStates.CHECK_VIOLATION);
    }

    private long insertMember() {
        String lgnId = "u" + UUID.randomUUID().toString().substring(0, 8);
        return jdbcTemplate.queryForObject(
                "INSERT INTO member (lgn_id, lgn_pwd, mbr_nm, eml_addr) VALUES (?, 'hash', '테스트', 'test@example.com') "
                        + "RETURNING mbr_no",
                Long.class, lgnId);
    }

    private String newAccountNo() {
        long seq = jdbcTemplate.queryForObject("SELECT nextval('acnt_no_seq')", Long.class);
        return "100" + String.format("%08d", seq) + "0";
    }

    private void assertRejected(String sql, String expectedSqlState) {
        assertThatThrownBy(() -> jdbcTemplate.execute(sql))
                .satisfies(e -> assertThat(SqlStates.of(e)).isEqualTo(expectedSqlState));
    }
}
