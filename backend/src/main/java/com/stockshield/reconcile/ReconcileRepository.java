package com.stockshield.reconcile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * reconcile_run·reconcile_result 읽기·쓰기 (ADM_REC_001 #3·#4).
 * 쿼리에는 JPA Auditing이 적용되지 않아 reg_ts·end_ts에 Clock 시각을 직접 넣음 (테이블 정의서 '공통 규칙' 17행).
 * reconcile_result는 INSERT만 함 ('공통 규칙' 6행).
 */
@Repository
public class ReconcileRepository {

    private static final String RUN_COLUMNS = """
            rccl_run_no, rccl_dt, trg_dv_cd, proc_mbr_no, run_stat_cd, tot_acnt_cnt, diff_acnt_cnt,
            fail_rsn_cont, reg_ts, end_ts
            """;

    // RUNNING 1건만 허용하는 부분 UNIQUE(uk_rccl_run_01)에 걸리면 아무것도 넣지 않음. 조회 후 INSERT는 동시 시작 때 둘 다 통과함
    private static final String START = """
            INSERT INTO reconcile_run (rccl_dt, trg_dv_cd, proc_mbr_no, run_stat_cd, reg_ts)
            VALUES (?, ?, ?, 'RUNNING', ?)
            ON CONFLICT (run_stat_cd) WHERE run_stat_cd = 'RUNNING' DO NOTHING
            RETURNING rccl_run_no
            """;

    private static final String INSERT_RESULT = """
            INSERT INTO reconcile_result (rccl_run_no, acnt_no, chk_item_cd, tck_cd, exp_val, actl_val, diff_yn, reg_ts)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbcTemplate;

    public ReconcileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** @return 이미 실행 중이면 empty */
    public Optional<Long> startRunning(String rcclDt, String trgDvCd, Long procMbrNo, LocalDateTime now) {
        return jdbcTemplate.query(START, (rs, n) -> rs.getLong(1), rcclDt, trgDvCd, procMbrNo, Timestamp.valueOf(now))
                .stream().findFirst();
    }

    /** 시작조차 못 한 정기 실행을 사유와 함께 남김 */
    public long insertFailed(String rcclDt, String trgDvCd, Long procMbrNo, String reason, LocalDateTime now) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO reconcile_run (rccl_dt, trg_dv_cd, proc_mbr_no, run_stat_cd, fail_rsn_cont, reg_ts, end_ts)
                VALUES (?, ?, ?, 'FAILED', ?, ?, ?)
                RETURNING rccl_run_no
                """, Long.class, rcclDt, trgDvCd, procMbrNo, reason, Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    /** 시작 시각이 cutoff 이전(포함)인 RUNNING을 FAILED로 */
    public int failStaleRunning(LocalDateTime cutoff, String reason, LocalDateTime now) {
        return jdbcTemplate.update("""
                UPDATE reconcile_run
                   SET run_stat_cd = 'FAILED', fail_rsn_cont = ?, end_ts = ?
                 WHERE run_stat_cd = 'RUNNING'
                   AND reg_ts <= ?
                """, reason, Timestamp.valueOf(now), Timestamp.valueOf(cutoff));
    }

    // 중단 정리가 먼저 FAILED로 닫은 실행은 바꾸지 않으려고 RUNNING 조건부
    public int markFailed(long rcclRunNo, String reason, LocalDateTime now) {
        return jdbcTemplate.update("""
                UPDATE reconcile_run
                   SET run_stat_cd = 'FAILED', fail_rsn_cont = ?, end_ts = ?
                 WHERE rccl_run_no = ?
                   AND run_stat_cd = 'RUNNING'
                """, reason, Timestamp.valueOf(now), rcclRunNo);
    }

    /** 결과 전체와 DONE을 한 커밋으로. 그사이 실행이 정리됐으면 결과도 남기지 않음 */
    @Transactional
    public void saveDone(long rcclRunNo, ReconcileSnapshotReader.Snapshot snapshot, LocalDateTime now) {
        int updated = jdbcTemplate.update("""
                UPDATE reconcile_run
                   SET run_stat_cd = 'DONE', tot_acnt_cnt = ?, diff_acnt_cnt = ?, end_ts = ?
                 WHERE rccl_run_no = ?
                   AND run_stat_cd = 'RUNNING'
                """, snapshot.totAcntCnt(), snapshot.diffAcntCnt(), Timestamp.valueOf(now), rcclRunNo);
        if (updated != 1) {
            throw new IllegalStateException("이미 RUNNING이 아닌 대사 실행 rcclRunNo=" + rcclRunNo);
        }
        insertResults(rcclRunNo, snapshot.items(), now);
    }

    public void insertResults(long rcclRunNo, List<ReconcileItem> items, LocalDateTime now) {
        Timestamp regTs = Timestamp.valueOf(now);
        jdbcTemplate.batchUpdate(INSERT_RESULT, items, BATCH_SIZE, (ps, item) -> {
            ps.setLong(1, rcclRunNo);
            ps.setString(2, item.acntNo());
            ps.setString(3, item.chkItemCd());
            ps.setString(4, item.tckCd());
            ps.setLong(5, item.expVal());
            ps.setLong(6, item.actlVal());
            ps.setString(7, item.diffYn());
            ps.setTimestamp(8, regTs);
        });
    }

    public Optional<ReconcileRun> findRun(long rcclRunNo) {
        return jdbcTemplate.query("SELECT " + RUN_COLUMNS + " FROM reconcile_run WHERE rccl_run_no = ?",
                ReconcileRepository::run, rcclRunNo).stream().findFirst();
    }

    /** 최신순. 같은 시각이면 나중 번호가 먼저 */
    public List<ReconcileRun> findRuns(long offset, int limit) {
        return jdbcTemplate.query("SELECT " + RUN_COLUMNS
                        + " FROM reconcile_run ORDER BY reg_ts DESC, rccl_run_no DESC LIMIT ? OFFSET ?",
                ReconcileRepository::run, limit, offset);
    }

    public long countRuns() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reconcile_run", Long.class);
    }

    /** 그날 정기 실행 중 마지막 1건. 수동 실행은 보지 않음 (API 명세서 'API_명세서' 31행) */
    public Optional<ReconcileRun> findLastScheduled(String rcclDt) {
        return jdbcTemplate.query("SELECT " + RUN_COLUMNS
                        + " FROM reconcile_run WHERE rccl_dt = ? AND trg_dv_cd = 'SCHEDULED'"
                        + " ORDER BY rccl_run_no DESC LIMIT 1",
                ReconcileRepository::run, rcclDt).stream().findFirst();
    }

    public List<ReconcileItem> findResults(long rcclRunNo, boolean diffOnly) {
        return jdbcTemplate.query("""
                SELECT acnt_no, chk_item_cd, tck_cd, exp_val, actl_val, diff_yn
                  FROM reconcile_result
                 WHERE rccl_run_no = ?
                   AND (? = FALSE OR diff_yn = 'Y')
                 ORDER BY acnt_no, CASE chk_item_cd WHEN 'DEPOSIT' THEN 0 ELSE 1 END, tck_cd
                """, (rs, n) -> new ReconcileItem(rs.getString("acnt_no"), rs.getString("chk_item_cd"),
                        rs.getString("tck_cd"), rs.getLong("exp_val"), rs.getLong("actl_val"),
                        "Y".equals(rs.getString("diff_yn"))),
                rcclRunNo, diffOnly);
    }

    private static ReconcileRun run(ResultSet rs, int rowNum) throws SQLException {
        return new ReconcileRun(rs.getLong("rccl_run_no"), rs.getString("rccl_dt"), rs.getString("trg_dv_cd"),
                rs.getObject("proc_mbr_no", Long.class), rs.getString("run_stat_cd"),
                rs.getObject("tot_acnt_cnt", Integer.class), rs.getObject("diff_acnt_cnt", Integer.class),
                rs.getString("fail_rsn_cont"), rs.getObject("reg_ts", LocalDateTime.class),
                rs.getObject("end_ts", LocalDateTime.class));
    }
}
