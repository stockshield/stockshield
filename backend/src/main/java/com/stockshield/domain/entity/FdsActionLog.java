package com.stockshield.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * FDS 조치 이력 (수정/삭제 절대 불가 감사로그).
 * @Immutable 로 UPDATE 차단. 삭제 금지는 Repository 레벨 및 DB 권한/트리거로 추가 보장 필요.
 */
@Getter
@Entity
@Immutable
@Table(name = "fds_action_log",
        indexes = {
                @Index(name = "IDX_ACT_LOG_01", columnList = "tgt_ord_no"),
                @Index(name = "IDX_ACT_LOG_02", columnList = "tgt_acnt_no")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class FdsActionLog extends BaseRegTimeEntity {

    /** 조치이력일련번호 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "act_log_seq")
    private Long actLogSeq;

    /** 대상주문번호 (잠금해제 시 NULL 허용, 감사로그 보존을 위해 FK 미설정) */
    @Column(name = "tgt_ord_no", length = 30)
    private String tgtOrdNo;

    /** 대상계좌번호 */
    @Column(name = "tgt_acnt_no", nullable = false, length = 20)
    private String tgtAcntNo;

    /** 조치구분 (Domain: APPROVE, REJECT, UNLOCK, TEMP_BLOCK) */
    @Column(name = "act_dv_cd", nullable = false, length = 20)
    private String actDvCd;

    /** 처리 관리자 (시스템 자동 조치 시 NULL 허용) (FK: member.mbr_no) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prc_mbr_no")
    private Member member;

    /** 조치 및 해제사유 (보안감사용 필수) */
    @Column(name = "act_rsn_cont", nullable = false, length = 500)
    private String actRsnCont;

    /** 조치 주체 구분 (Domain: SYSTEM, ADMIN) */
    @Column(name = "act_actor_dv", nullable = false, length = 10)
    private String actActorDv;
}

