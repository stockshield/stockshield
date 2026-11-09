package com.stockshield.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
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

import java.io.Serializable;
import java.math.BigDecimal;

/** 시세 분봉 (실시간 차트 및 FDS 거래량 폭증 통계용) - 복합 PK(tck_cd, bs_mi) */
@Getter
@Entity
@Table(name = "price_minute")
@Check(constraints = "trd_vol >= 0")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class PriceMinute extends BaseRegTimeEntity {

    @EmbeddedId
    private PriceMinuteId id;

    /** 종목코드 (FK: stock_master.tck_cd) */
    @MapsId("tckCd")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tck_cd")
    private StockMaster stockMaster;

    /** 시가 */
    @Column(name = "open_prc", nullable = false, precision = 15, scale = 0)
    private BigDecimal openPrc;

    /** 고가 */
    @Column(name = "high_prc", nullable = false, precision = 15, scale = 0)
    private BigDecimal highPrc;

    /** 저가 */
    @Column(name = "low_prc", nullable = false, precision = 15, scale = 0)
    private BigDecimal lowPrc;

    /** 종가 */
    @Column(name = "close_prc", nullable = false, precision = 15, scale = 0)
    private BigDecimal closePrc;

    /** 분당거래량 (CHECK trd_vol >= 0) */
    @Column(name = "trd_vol", nullable = false)
    private Long trdVol;

    @Getter
    @Embeddable
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class PriceMinuteId implements Serializable {

        @Column(name = "tck_cd", length = 10)
        private String tckCd;

        /** 기준분 (YYYYMMDDHHMI) */
        @Column(name = "bs_mi", length = 12)
        private String bsMi;
    }
}

