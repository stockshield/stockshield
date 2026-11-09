package com.stockshield.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/** 원장 정합성 점검 결과 (일일 마감 대사 배치용) - 복합 PK(rccl_dt, acnt_no) */
@Getter
@Entity
@Table(name = "reconcile_result",
        indexes = @Index(name = "IDX_RECONCILE_01", columnList = "diff_yn"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ReconcileResult extends BaseRegTimeEntity {

    @EmbeddedId
    private ReconcileResultId id;

    /** 계좌번호 (FK: account.acnt_no) */
    @MapsId("acntNo")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "acnt_no")
    private Account account;

    /** 예수금 기대값 (이론적 잔고) */
    @Column(name = "dps_exp_amt", nullable = false, precision = 19, scale = 0)
    private BigDecimal dpsExpAmt;

    /** 예수금 실제값 (원장 잔고) */
    @Column(name = "dps_act_amt", nullable = false, precision = 19, scale = 0)
    private BigDecimal dpsActAmt;

    /** 보유수량 기대값 */
    @Column(name = "hld_exp_qty", nullable = false)
    private Long hldExpQty;

    /** 보유수량 실제값 */
    @Column(name = "hld_act_qty", nullable = false)
    private Long hldActQty;

    /** 불일치여부 (Domain: Y, N) */
    @Column(name = "diff_yn", nullable = false, length = 1, columnDefinition = "CHAR(1)")
    private String diffYn;

    @Getter
    @Embeddable
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class ReconcileResultId implements Serializable {

        /** 대사기준일자 (YYYYMMDD) */
        @Column(name = "rccl_dt", length = 8)
        private String rcclDt;

        @Column(name = "acnt_no", length = 20)
        private String acntNo;
    }
}

