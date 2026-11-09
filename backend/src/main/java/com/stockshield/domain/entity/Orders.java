package com.stockshield.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

import java.math.BigDecimal;

/** 주문 (동시성 제어 및 멱등성 보장 테이블) - 주문 상태 변경 추적 */
@Getter
@Entity
@Table(name = "orders",
        uniqueConstraints = @UniqueConstraint(name = "UK_ORDERS_01", columnNames = "idmp_key"),
        indexes = @Index(name = "IDX_ORDERS_01", columnList = "acnt_no, reg_ts DESC"))
@Check(constraints = "ord_qty > 0 AND ord_prc > 0")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Orders extends BaseTimeEntity {

    /** 주문번호 (채번 규칙: YYYYMMDD + 시퀀스, 직접 할당) */
    @Id
    @Column(name = "ord_no", length = 30)
    private String ordNo;

    /** 계좌번호 (FK: account.acnt_no) */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "acnt_no", nullable = false)
    private Account account;

    /** 종목코드 (FK: stock_master.tck_cd) */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tck_cd", nullable = false)
    private StockMaster stockMaster;

    /** 주문구분 (Domain: BUY, SELL) */
    @Column(name = "ord_dv_cd", nullable = false, length = 10)
    private String ordDvCd;

    /** 주문수량 (CHECK ord_qty > 0) */
    @Column(name = "ord_qty", nullable = false)
    private Long ordQty;

    /** 주문단가 - 시장가 기준 (CHECK ord_prc > 0) */
    @Column(name = "ord_prc", nullable = false, precision = 15, scale = 0)
    private BigDecimal ordPrc;

    /** 주문상태 (Domain: FILLED, REJECTED) */
    @Column(name = "ord_stat_cd", nullable = false, length = 10)
    private String ordStatCd;

    /** 멱등키 (UUID, UK_ORDERS_01) */
    @Column(name = "idmp_key", nullable = false, length = 50)
    private String idmpKey;
}

