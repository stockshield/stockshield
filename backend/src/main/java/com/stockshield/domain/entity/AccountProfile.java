package com.stockshield.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 계좌 프로필 (FDS 금액이상 탐지 기준) - 메인 원장 락 경합 회피용 (PK 공유 1:1) */
@Getter
@Entity
@Table(name = "account_profile")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class AccountProfile {

    /** 계좌번호 (PK = FK: account.acnt_no, ON DELETE CASCADE) */
    @Id
    @Column(name = "acnt_no", length = 20)
    private String acntNo;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "acnt_no")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Account account;

    /** 평균주문금액 (초기값 50만원) */
    @Builder.Default
    @Column(name = "avg_ord_amt", nullable = false, precision = 19, scale = 0)
    private BigDecimal avgOrdAmt = BigDecimal.valueOf(500_000L);

    /** 주문금액표준편차 */
    @Builder.Default
    @Column(name = "std_dev_amt", nullable = false, precision = 19, scale = 2)
    private BigDecimal stdDevAmt = BigDecimal.ZERO;

    /** 최근갱신일시 (배치 또는 비동기 갱신) */
    @LastModifiedDate
    @Column(name = "upd_ts", nullable = false)
    private LocalDateTime updTs;
}

