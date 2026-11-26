package com.stockshield.ledger;

import org.hibernate.annotations.Immutable;

import com.stockshield.common.entity.BaseRegTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * FDS 조치 이력 - INSERT 전용 감사 기록 (SYS_SEC_001 #3, FDS_ALT_001 구현방안 '감사').
 * 계좌를 바꾸는 쪽(원장·관리자 API)만 쓰고 FDS는 쓰지 않음 (연결 합의서 4절).
 */
@Getter
@Entity
@Immutable
@Table(name = "fds_action_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FdsActionLog extends BaseRegTimeEntity {

    /** TEMP_LOCK 이력의 사유. 내 계좌의 잠긴 이유(API 18행 lockRsn)와 같은 문구 */
    public static final String TEMP_LOCK_REASON = "출금 비밀번호 5회 오류";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "act_log_seq")
    private Long actLogSeq;

    @Column(name = "tgt_dtc_seq", updatable = false)
    private Long tgtDtcSeq;

    @Column(name = "tgt_acnt_no", length = 20, updatable = false)
    private String tgtAcntNo;

    @Column(name = "act_dv_cd", length = 20, nullable = false, updatable = false)
    private String actDvCd;

    @Column(name = "proc_mbr_no", updatable = false)
    private Long procMbrNo;

    @Column(name = "act_rsn_cont", length = 500, nullable = false, updatable = false)
    private String actRsnCont;

    @Column(name = "act_actor_dv_cd", length = 10, nullable = false, updatable = false)
    private String actActorDvCd;

    /**
     * 출금 비밀번호 5회 오류로 시스템이 잠근 기록.
     * @param dtcSeq 잠금의 원인인 LOCKED 탐지 기록(recordWithdrawal이 돌려준 번호)
     */
    public static FdsActionLog tempLock(String acntNo, long dtcSeq) {
        FdsActionLog log = new FdsActionLog();
        log.tgtDtcSeq = dtcSeq;
        log.tgtAcntNo = acntNo;
        log.actDvCd = "TEMP_LOCK";
        log.actRsnCont = TEMP_LOCK_REASON;
        log.actActorDvCd = "SYSTEM";
        return log;
    }

    /** 관리자 탐지 확인. 계좌는 비움('테이블정의서' 180행: 탐지 확인만 할 때 NULL) */
    public static FdsActionLog check(long dtcSeq, long adminMbrNo, String reason) {
        return byAdmin("CHECK", dtcSeq, null, adminMbrNo, reason);
    }

    /** 관리자 잠금(LOCK)·해제(UNLOCK). 탐지는 비움('테이블정의서' 179행: 잠금·해제만 할 때 NULL) */
    public static FdsActionLog accountStatus(String actDvCd, String acntNo, long adminMbrNo, String reason) {
        return byAdmin(actDvCd, null, acntNo, adminMbrNo, reason);
    }

    private static FdsActionLog byAdmin(String actDvCd, Long dtcSeq, String acntNo, long adminMbrNo, String reason) {
        FdsActionLog log = new FdsActionLog();
        log.tgtDtcSeq = dtcSeq;
        log.tgtAcntNo = acntNo;
        log.actDvCd = actDvCd;
        log.procMbrNo = adminMbrNo;
        log.actRsnCont = reason;
        log.actActorDvCd = "ADMIN";
        return log;
    }
}
