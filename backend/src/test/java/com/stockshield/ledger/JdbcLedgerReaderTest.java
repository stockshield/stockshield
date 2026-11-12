package com.stockshield.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.stockshield.fds.api.FilledOrderView;
import com.stockshield.fds.api.LedgerReader;
import com.stockshield.support.IntegrationTestSupport;

/**
 * FDS가 원장을 읽는 LedgerReader를 실제 PostgreSQL에서 확인한다.
 * FDS_WDR_001 #1(24시간 DONE 합계, 계좌 락을 잡은 같은 트랜잭션), FDS_TRD_001 #2(체결 주문의 저장된 IP·기기), 연결 합의서 4절·6절·7절
 *
 * 테스트마다 새 회원·계좌를 만들어 다른 테스트의 데이터와 섞이지 않게 한다(앱 계정에는 DELETE 권한이 없다).
 */
class JdbcLedgerReaderTest extends IntegrationTestSupport {

    @Autowired
    LedgerReader ledgerReader;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("24시간 합계는 그 계좌의 DONE 출금만 더하고, 기준 시각과 같은 출금은 포함한다")
    void sumDoneWithdrawalSinceCountsOnlyDoneWithdrawalsOfTheAccount() {
        String acntNo = insertAccount();
        String otherAcntNo = insertAccount();
        LocalDateTime since = LocalDateTime.of(2026, 11, 11, 9, 0, 0);

        insertWithdrawal(acntNo, 1_000_000, "DONE", since.minusSeconds(1));      // 기준 시각 1초 전: 제외
        insertWithdrawal(acntNo, 2_000_000, "DONE", since);                      // 기준 시각과 같음: 포함
        insertWithdrawal(acntNo, 300_000, "DONE", since.plusHours(1));           // 포함
        insertWithdrawal(acntNo, 4_000_000, "REJECTED", since.plusHours(1));     // 거절: 제외
        insertWithdrawal(otherAcntNo, 7_000_000, "DONE", since.plusHours(1));    // 다른 계좌: 제외

        Long sum = transactionTemplate.execute(status -> ledgerReader.sumDoneWithdrawalSince(acntNo, since));

        assertThat(sum).isEqualTo(2_300_000L);
    }

    @Test
    @DisplayName("출금이 없으면 합계는 0이다")
    void sumIsZeroWithoutWithdrawals() {
        String acntNo = insertAccount();

        Long sum = transactionTemplate.execute(
                status -> ledgerReader.sumDoneWithdrawalSince(acntNo, LocalDateTime.of(2026, 11, 11, 9, 0)));

        assertThat(sum).isZero();
    }

    @Test
    @DisplayName("24시간 합계는 트랜잭션 밖에서 부르면 실패한다(MANDATORY, 계좌 락과 같은 트랜잭션에서만)")
    void sumRequiresCallerTransaction() {
        String acntNo = insertAccount();

        assertThatThrownBy(() -> ledgerReader.sumDoneWithdrawalSince(acntNo, LocalDateTime.of(2026, 11, 11, 9, 0)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("체결 주문은 회원번호와 주문에 저장된 IP·기기·User-Agent, 체결금액, 주문 시각으로 읽는다")
    void findFilledOrderReadsStoredRequestInfo() {
        String acntNo = insertAccount();
        long mbrNo = jdbcTemplate.queryForObject("SELECT mbr_no FROM account WHERE acnt_no = ?", Long.class, acntNo);
        LocalDateTime regTs = LocalDateTime.of(2026, 11, 12, 10, 15, 30);
        String ordNo = insertOrder(acntNo, "FILLED", regTs);
        insertTrade(ordNo, acntNo, 3, 214_500);

        Optional<FilledOrderView> view = ledgerReader.findFilledOrder(ordNo);

        assertThat(view).contains(new FilledOrderView(ordNo, mbrNo, acntNo, "005930", "BUY",
                "203.0.113.7", "6f1c2d3e-0000-4000-8000-000000000001", "test-agent", 643_500L, regTs));
    }

    @Test
    @DisplayName("거절 주문과 없는 주문번호는 읽지 않는다")
    void findFilledOrderIgnoresRejectedAndUnknownOrders() {
        String acntNo = insertAccount();
        String rejected = insertOrder(acntNo, "REJECTED", LocalDateTime.of(2026, 11, 12, 10, 0));

        assertThat(ledgerReader.findFilledOrder(rejected)).isEmpty();
        assertThat(ledgerReader.findFilledOrder("O-no-such-order")).isEmpty();
    }

    private String insertAccount() {
        String lgnId = "u" + UUID.randomUUID().toString().substring(0, 8);
        long mbrNo = jdbcTemplate.queryForObject(
                "INSERT INTO member (lgn_id, lgn_pwd, mbr_nm, eml_addr) VALUES (?, 'hash', '테스트', 'test@example.com') "
                        + "RETURNING mbr_no", Long.class, lgnId);
        long seq = jdbcTemplate.queryForObject("SELECT nextval('acnt_no_seq')", Long.class);
        String acntNo = "100" + String.format("%08d", seq) + "0";
        jdbcTemplate.update("INSERT INTO account (acnt_no, mbr_no, init_pkg_cd, init_dps_amt, dps_bal, wdr_pwd) "
                + "VALUES (?, ?, 'PKG_50M', 50000000, 50000000, 'hash')", acntNo, mbrNo);
        return acntNo;
    }

    private void insertWithdrawal(String acntNo, long amount, String status, LocalDateTime regTs) {
        boolean done = "DONE".equals(status);
        jdbcTemplate.update("INSERT INTO withdrawal (wdr_no, acnt_no, wdr_amt, rcv_bank_nm, rcv_acnt_no, wdr_stat_cd, "
                        + "rjct_rsn_cd, post_dps_bal, idmp_key, conn_ip, user_agt, trc_id, reg_ts) "
                        + "VALUES (?, ?, ?, '테스트은행', '1234567890', ?, ?, ?, ?, '127.0.0.1', '-', ?, ?)",
                "W" + UUID.randomUUID().toString().substring(0, 20), acntNo, amount, status,
                done ? null : "INSUFFICIENT_BALANCE", done ? 0L : null,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), regTs);
    }

    private String insertOrder(String acntNo, String status, LocalDateTime regTs) {
        jdbcTemplate.update("INSERT INTO stock_master (tck_cd, tck_nm, mkt_cd) VALUES ('005930', '삼성전자', 'KOSPI') "
                + "ON CONFLICT (tck_cd) DO NOTHING");
        boolean filled = "FILLED".equals(status);
        String ordNo = "O" + UUID.randomUUID().toString().substring(0, 20);
        jdbcTemplate.update("INSERT INTO orders (ord_no, acnt_no, tck_cd, ord_dv_cd, ord_qty, ord_prc, prc_ts, "
                        + "ord_stat_cd, rjct_rsn_cd, idmp_key, conn_ip, dvc_id, user_agt, trc_id, reg_ts) "
                        + "VALUES (?, ?, '005930', 'BUY', 3, ?, ?, ?, ?, ?, '203.0.113.7', "
                        + "'6f1c2d3e-0000-4000-8000-000000000001', 'test-agent', ?, ?)",
                ordNo, acntNo, filled ? 214_500L : null, filled ? regTs : null, status,
                filled ? null : "PRICE_STALE", UUID.randomUUID().toString(), UUID.randomUUID().toString(), regTs);
        return ordNo;
    }

    private void insertTrade(String ordNo, String acntNo, long qty, long price) {
        jdbcTemplate.update("INSERT INTO trade_history (ord_no, acnt_no, tck_cd, trd_dv_cd, trd_qty, trd_prc, trd_amt, "
                        + "post_dps_bal, post_hld_qty) VALUES (?, ?, '005930', 'BUY', ?, ?, ?, 0, ?)",
                ordNo, acntNo, qty, price, qty * price, qty);
    }
}
