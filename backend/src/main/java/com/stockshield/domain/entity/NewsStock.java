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

import java.io.Serializable;

/** 뉴스-종목 N:M 매핑 관계 - 복합 PK(news_id, tck_cd) */
@Getter
@Entity
@Table(name = "news_stock")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class NewsStock extends BaseRegTimeEntity {

    @EmbeddedId
    private NewsStockId id;

    /** 기사고유ID (FK: news.news_id) */
    @MapsId("newsId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "news_id")
    private News news;

    /** 종목코드 (FK: stock_master.tck_cd) */
    @MapsId("tckCd")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tck_cd")
    private StockMaster stockMaster;

    @Getter
    @Embeddable
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class NewsStockId implements Serializable {

        @Column(name = "news_id", length = 50)
        private String newsId;

        @Column(name = "tck_cd", length = 10)
        private String tckCd;
    }
}

