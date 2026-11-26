package com.stockshield.ledger;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.stockshield.fds.api.FilledOrderView;
import com.stockshield.fds.api.LedgerReader;

/**
 * FDS가 원장을 읽는 쿼리 2개. 출금·주문 엔티티가 아직 없어 SQL로 쓴다.
 * 트랜잭션 전파는 LedgerReader 인터페이스에 선언되어 있고, 여기에는 @Transactional을 붙이지 않는다.
 * JdbcTemplate은 JPA 트랜잭션과 같은 DB 연결을 쓰므로, 출금 트랜잭션 안에서는 계좌 락을 잡은 같은 트랜잭션에서 합계를 구한다.
 * (FDS_WDR_001 #1·구현방안, FDS_TRD_001 #2, 연결 합의서 4절·6절·7절)
 */
@Repository
public class JdbcLedgerReader implements LedgerReader {

    /** 경계 시각(since)도 포함한다. 부분 인덱스 IDX_WDR_02 (acnt_no, reg_ts) WHERE wdr_stat_cd = 'DONE'을 탄다 */
    private static final String SUM_DONE_WITHDRAWAL = """
            SELECT COALESCE(SUM(wdr_amt), 0)
              FROM withdrawal
             WHERE acnt_no = ?
               AND wdr_stat_cd = 'DONE'
               AND reg_ts >= ?
            """;

    /** 체결 주문 하나에 체결 내역은 하나다(UK_TRADE_01). 회원번호는 계좌에서 읽는다 */
    private static final String FIND_FILLED_ORDER = """
            SELECT o.ord_no, a.mbr_no, o.acnt_no, o.tck_cd, o.ord_dv_cd,
                   o.conn_ip, o.dvc_id, o.user_agt, t.trd_amt, o.reg_ts
              FROM orders o
              JOIN account a ON a.acnt_no = o.acnt_no
              JOIN trade_history t ON t.ord_no = o.ord_no
             WHERE o.ord_no = ?
               AND o.ord_stat_cd = 'FILLED'
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcLedgerReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long sumDoneWithdrawalSince(String acntNo, LocalDateTime since) {
        Long sum = jdbcTemplate.queryForObject(SUM_DONE_WITHDRAWAL, Long.class, acntNo, since);
        return sum == null ? 0L : sum;
    }

    @Override
    public Optional<FilledOrderView> findFilledOrder(String ordNo) {
        List<FilledOrderView> rows = jdbcTemplate.query(FIND_FILLED_ORDER, JdbcLedgerReader::toView, ordNo);
        return rows.stream().findFirst();
    }

    private static FilledOrderView toView(ResultSet rs, int rowNum) throws SQLException {
        return new FilledOrderView(
                rs.getString("ord_no"),
                rs.getLong("mbr_no"),
                rs.getString("acnt_no"),
                rs.getString("tck_cd"),
                rs.getString("ord_dv_cd"),
                rs.getString("conn_ip"),
                rs.getString("dvc_id"),
                rs.getString("user_agt"),
                rs.getLong("trd_amt"),
                rs.getObject("reg_ts", LocalDateTime.class));
    }
}
