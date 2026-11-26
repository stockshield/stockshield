package com.stockshield.reconcile;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.springframework.test.web.servlet.MvcResult;

import com.stockshield.support.LedgerTestSupport;

/**
 * 원장 대사 테스트 공통 도우미. 설정을 더하지 않아 다른 원장 테스트와 같은 컨텍스트를 씀.
 * 대사는 공용 DB의 모든 계좌를 보므로(다른 테스트가 일부러 틀어 둔 계좌도 있음) 결과는 자기 계좌 것만 확인함.
 */
abstract class ReconcileTestSupport extends LedgerTestSupport {

    // RUNNING이 남으면 같은 DB를 쓰는 다음 대사가 모두 409가 됨
    @AfterEach
    void closeLeftoverRunning() {
        db.update("UPDATE reconcile_run SET run_stat_cd = 'FAILED', fail_rsn_cont = '테스트 정리', end_ts = CURRENT_TIMESTAMP"
                + " WHERE run_stat_cd = 'RUNNING'");
    }

    /** 다른 실행이 진행 중인 상태를 만듦 */
    protected long insertRunning(LocalDateTime regTs) {
        return db.queryForObject("""
                INSERT INTO reconcile_run (rccl_dt, trg_dv_cd, run_stat_cd, reg_ts)
                VALUES ('20261116', 'SCHEDULED', 'RUNNING', ?) RETURNING rccl_run_no
                """, Long.class, Timestamp.valueOf(regTs));
    }

    protected MvcResult runManually(Tokens admin) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/reconcile").header("Authorization", bearer(admin))).andReturn();
    }

    protected Map<String, Object> runRow(long rcclRunNo) {
        return db.queryForMap("SELECT * FROM reconcile_run WHERE rccl_run_no = ?", rcclRunNo);
    }

    /** 이 실행에서 이 계좌의 결과 (DEPOSIT 먼저, 그다음 종목코드 순) */
    protected List<Map<String, Object>> resultsOf(long rcclRunNo, String acntNo) {
        return db.queryForList("""
                SELECT chk_item_cd, tck_cd, exp_val, actl_val, diff_yn
                  FROM reconcile_result
                 WHERE rccl_run_no = ? AND acnt_no = ?
                 ORDER BY CASE chk_item_cd WHEN 'DEPOSIT' THEN 0 ELSE 1 END, tck_cd
                """, rcclRunNo, acntNo);
    }

    protected static long num(Object v) {
        return ((Number) v).longValue();
    }
}
