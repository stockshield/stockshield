package com.stockshield.reconcile;

import java.time.LocalDateTime;

/** reconcile_run 한 행 (테이블 정의서 '테이블정의서' 187~199행) */
public record ReconcileRun(long rcclRunNo, String rcclDt, String trgDvCd, Long procMbrNo, String runStatCd,
                           Integer totAcntCnt, Integer diffAcntCnt, String failRsnCont,
                           LocalDateTime regTs, LocalDateTime endTs) {

    public static final String SCHEDULED = "SCHEDULED";
    public static final String MANUAL = "MANUAL";
    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    /** 불일치가 있거나 실패했을 때만 관제 경보 (ADM_REC_001 #4, 문서 수정 대기 목록 #8) */
    public boolean needsAlert() {
        return FAILED.equals(runStatCd) || (diffAcntCnt != null && diffAcntCnt > 0);
    }
}
