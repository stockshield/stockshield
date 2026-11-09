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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 뉴스 기사 및 LLM AI 분석 메타데이터 */
@Getter
@Entity
@Table(name = "news",
        indexes = @Index(name = "IDX_NEWS_01", columnList = "pub_ts DESC"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class News extends BaseRegTimeEntity {

    /** 기사고유ID (URL 해시 등 중복수집 방어) */
    @Id
    @Column(name = "news_id", length = 50)
    private String newsId;

    /** 기사제목 */
    @Column(name = "news_titl", nullable = false, length = 500)
    private String newsTitl;

    /** 기사본문 */
    @Column(name = "news_cont", nullable = false, columnDefinition = "TEXT")
    private String newsCont;

    /** AI 3줄 요약 내용 (비동기 처리, NULL 허용) */
    @Column(name = "ai_smry_cont", length = 1000)
    private String aiSmryCont;

    /** AI 감성점수 -1.00 ~ 1.00 (비동기 처리, NULL 허용) */
    @Column(name = "ai_sntm_scr", precision = 3, scale = 2)
    private BigDecimal aiSntmScr;

    /** 기사발행일시 */
    @Column(name = "pub_ts", nullable = false)
    private LocalDateTime pubTs;
}

