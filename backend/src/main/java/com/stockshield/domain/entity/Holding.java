package com.stockshield.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
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
import org.hibernate.annotations.Check;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 원장 보유종목 잔고 - 비관적 락(Pessimistic Lock) 대상. 복합 PK(acnt_no, tck_cd).
 * (mod_ts 만 존재하므로 BaseTimeEntity 미상속)
 */
@Getter
@Entity
@Table(name = "holding")
@Check(constraints = "hld_qty >= 0")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Holding {

    @EmbeddedId
    private HoldingId id;

    /** 계좌번호 (FK: account.acnt_no) */
    @MapsId("acntNo")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "acnt_no")
    private Account account;

    /** 종목코드 (FK: stock_master.tck_cd) */
    @MapsId("tckCd")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tck_cd")
    private StockMaster stockMaster;

    /** 보유수량 (CHECK hld_qty >= 0) */
    @Builder.Default
    @Column(name = "hld_qty", nullable = false)
    private Long hldQty = 0L;

    /** 평균매입단가 (소수점 허용) */
    @Builder.Default
    @Column(name = "avg_buy_prc", nullable = false, precision = 15, scale = 2)
    private BigDecimal avgBuyPrc = BigDecimal.ZERO;

    /** 최근수정일시 */
    @LastModifiedDate
    @Column(name = "mod_ts", nullable = false)
    private LocalDateTime modTs;

    @Getter
    @Embeddable
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class HoldingId implements Serializable {

        @Column(name = "acnt_no", length = 20)
        private String acntNo;

        @Column(name = "tck_cd", length = 10)
        private String tckCd;
    }
}

