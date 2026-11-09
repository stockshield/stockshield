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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

import java.math.BigDecimal;

/** 원장 거래명세 (계정계 원장 잔액 스냅샷 포함 - 핵심 테이블) */
@Getter
@Entity
@Table(name = "trade_history",
        uniqueConstraints = @UniqueConstraint(name = "UK_TRADE_01", columnNames = "ord_no"),
        indexes = @Index(name = "IDX_TRADE_01", columnList = "acnt_no, reg_ts DESC"))
@Check(constraints = "trd_qty > 0 AND trd_prc > 0")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class TradeHistory extends BaseRegTimeEntity {

    /** 체결일련번호 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "trd_seq")
    private Long trdSeq;

    /** 원주문번호 (FK: orders.ord_no, UK_TRADE_01 → 1주문:1체결) */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ord_no", nullable = false)
    private Orders orders;

    /** 계좌번호 (FK: account.acnt_no) */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "acnt_no", nullable = false)
    private Account account;

    /** 종목코드 (정의서상 FK 미명시 → 단순 컬럼) */
    @Column(name = "tck_cd", nullable = false, length = 10)
    private String tckCd;

    /** 체결구분 (Domain: BUY, SELL) */
    @Column(name = "trd_dv_cd", nullable = false, length = 10)
    private String trdDvCd;

    /** 체결수량 (CHECK trd_qty > 0) */
    @Column(name = "trd_qty", nullable = false)
    private Long trdQty;

    /** 체결단가 (CHECK trd_prc > 0) */
    @Column(name = "trd_prc", nullable = false, precision = 15, scale = 0)
    private BigDecimal trdPrc;

    /** 체결금액 (qty * prc, 수수료/세금 배제) */
    @Column(name = "trd_amt", nullable = false, precision = 19, scale = 0)
    private BigDecimal trdAmt;

    /** 거래후 예수금잔액 */
    @Column(name = "post_dps_bal", nullable = false, precision = 19, scale = 0)
    private BigDecimal postDpsBal;

    /** 거래후 보유수량 (체결 시점 잔고 스냅샷) */
    @Column(name = "post_hld_qty", nullable = false)
    private Long postHldQty;
}

