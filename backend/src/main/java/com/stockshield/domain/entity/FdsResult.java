package com.stockshield.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** FDS 탐지 및 분석 결과 (1주문:1결과 정합성, PK 공유 1:1) */
@Getter
@Entity
@Table(name = "fds_result")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class FdsResult extends BaseRegTimeEntity {

    /** 대상주문번호 (PK = FK: orders.ord_no) */
    @Id
    @Column(name = "ord_no", length = 30)
    private String ordNo;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ord_no")
    private Orders orders;

    /** 위반규칙명 */
    @Column(name = "viol_rule_nm", nullable = false, length = 50)
    private String violRuleNm;

    /** 위반규칙 상세 내역 (JSON 문자열). INSERT 시 jsonb 타입 캐스팅을 위해 JdbcTypeCode(JSON) 병행 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fds_rsn_data", nullable = false, columnDefinition = "jsonb")
    private String fdsRsnData;

    /** 누적경고횟수 */
    @Builder.Default
    @Column(name = "warn_cnt", nullable = false)
    private Integer warnCnt = 1;

    /** 탐지출처 (Domain: INTERNAL, EXTERNAL) */
    @Column(name = "dtc_src_cd", nullable = false, length = 10)
    private String dtcSrcCd;
}

