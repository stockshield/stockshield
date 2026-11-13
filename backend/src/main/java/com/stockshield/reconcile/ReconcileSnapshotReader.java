package com.stockshield.reconcile;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 모든 계좌의 원장과 내역을 한 스냅샷에서 비교함 (ADM_REC_001 #1·#2, 테이블 정의서 '공통 규칙' 12~14행).
 * 계좌 락을 잡지 않으므로 대사 중에도 주문·출금이 멈추지 않음 (TRD_LED_001 '테스트' (6)).
 */
@Component
public class ReconcileSnapshotReader {

    public record Snapshot(int totAcntCnt, List<ReconcileItem> items) {
        public int diffAcntCnt() {
            return (int) items.stream().filter(ReconcileItem::diff).map(ReconcileItem::acntNo).distinct().count();
        }
    }

    /** 예수금 = 초기 예수금 − 매수 체결금액 + 매도 체결금액 − 완료 출금 */
    private static final String DEPOSIT = """
            SELECT a.acnt_no,
                   a.init_dps_amt - COALESCE(t.buy_amt, 0) + COALESCE(t.sell_amt, 0) - COALESCE(w.done_amt, 0) AS exp_val,
                   a.dps_bal AS actl_val
              FROM account a
              LEFT JOIN (SELECT acnt_no,
                                SUM(trd_amt) FILTER (WHERE trd_dv_cd = 'BUY')  AS buy_amt,
                                SUM(trd_amt) FILTER (WHERE trd_dv_cd = 'SELL') AS sell_amt
                           FROM trade_history
                          GROUP BY acnt_no) t ON t.acnt_no = a.acnt_no
              LEFT JOIN (SELECT acnt_no, SUM(wdr_amt) AS done_amt
                           FROM withdrawal
                          WHERE wdr_stat_cd = 'DONE'
                          GROUP BY acnt_no) w ON w.acnt_no = a.acnt_no
             ORDER BY a.acnt_no
            """;

    /** 보유 수량 = 매수 수량 − 매도 수량. 내역과 잔고 중 한쪽에만 있는 종목도 남기려고 FULL OUTER JOIN */
    private static final String HOLDING = """
            SELECT COALESCE(t.acnt_no, h.acnt_no) AS acnt_no,
                   COALESCE(t.tck_cd, h.tck_cd) AS tck_cd,
                   COALESCE(t.net_qty, 0) AS exp_val,
                   COALESCE(h.hld_qty, 0) AS actl_val,
                   (t.acnt_no IS NULL OR h.acnt_no IS NULL) AS one_side_only
              FROM (SELECT acnt_no, tck_cd,
                           SUM(CASE WHEN trd_dv_cd = 'BUY' THEN trd_qty ELSE -trd_qty END) AS net_qty
                      FROM trade_history
                     GROUP BY acnt_no, tck_cd) t
              FULL OUTER JOIN holding h ON h.acnt_no = t.acnt_no AND h.tck_cd = t.tck_cd
             ORDER BY 1, 2
            """;

    private final JdbcTemplate jdbcTemplate;

    public ReconcileSnapshotReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // PostgreSQL REPEATABLE READ는 첫 쿼리 시점 스냅샷을 끝까지 씀. 두 쿼리가 같은 시점을 보게 하려고 한 트랜잭션에 둠
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot read() {
        List<ReconcileItem> items = new ArrayList<>(jdbcTemplate.query(DEPOSIT, (rs, n) -> {
            long exp = rs.getLong("exp_val");
            long actl = rs.getLong("actl_val");
            return new ReconcileItem(rs.getString("acnt_no"), ReconcileItem.DEPOSIT, null, exp, actl, exp != actl);
        }));
        int totAcntCnt = items.size();
        items.addAll(jdbcTemplate.query(HOLDING, (rs, n) -> {
            long exp = rs.getLong("exp_val");
            long actl = rs.getLong("actl_val");
            // 수량이 같아도 한쪽에만 있으면 불일치 ('공통 규칙' 13행)
            boolean diff = exp != actl || rs.getBoolean("one_side_only");
            return new ReconcileItem(rs.getString("acnt_no"), ReconcileItem.HOLDING, rs.getString("tck_cd"),
                    exp, actl, diff);
        }));
        return new Snapshot(totAcntCnt, items);
    }
}
