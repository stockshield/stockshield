package com.stockshield.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
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
import org.hibernate.annotations.Check;

import java.math.BigDecimal;

/**
 * 계좌 (모의투자 원장 마스터) - 비관적 락(Pessimistic Lock) 대상.
 * 조회 시 AccountRepository 에서 @Lock(LockModeType.PESSIMISTIC_WRITE) 적용.
 */
@Getter
@Entity
@Table(name = "account",
        indexes = @Index(name = "IDX_ACCOUNT_01", columnList = "mbr_no"))
@Check(constraints = "dps_bal >= 0")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Account extends BaseTimeEntity {

    /** 계좌번호 (채번 후 직접 할당) */
    @Id
    @Column(name = "acnt_no", length = 20)
    private String acntNo;

    /** 회원고유번호 (FK: member.mbr_no, ON DELETE RESTRICT) */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mbr_no", nullable = false)
    private Member member;

    /** 예수금잔액 (CHECK dps_bal >= 0) */
    @Builder.Default
    @Column(name = "dps_bal", nullable = false, precision = 19, scale = 0)
    private BigDecimal dpsBal = BigDecimal.valueOf(10_000_000L);

    /** 계좌상태 (Domain: ACTIVE, TEMP_LOCKED, LOCKED) */
    @Builder.Default
    @Column(name = "acnt_stat_cd", nullable = false, length = 10)
    private String acntStatCd = "ACTIVE";

    /** 안심모드 가입 여부 (Domain: Y, N) */
    @Builder.Default
    @Column(name = "safe_mode_yn", nullable = false, length = 1, columnDefinition = "CHAR(1)")
    private String safeModeYn = "N";
}

