package com.stockshield.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 종목 마스터 (기준 정보) */
@Getter
@Entity
@Table(name = "stock_master",
        indexes = @Index(name = "IDX_STOCK_MST_01", columnList = "tck_nm"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class StockMaster extends BaseRegTimeEntity {

    /** 종목코드 (예: '005930') */
    @Id
    @Column(name = "tck_cd", length = 10)
    private String tckCd;

    /** 종목명 */
    @Column(name = "tck_nm", nullable = false, length = 100)
    private String tckNm;

    /** 시장구분 (Domain: KOSPI, KOSDAQ) */
    @Column(name = "mkt_cd", nullable = false, length = 10)
    private String mktCd;
}

