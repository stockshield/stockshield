package com.stockshield.common.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

/**
 * 등록 시각(reg_ts)만 있는 테이블의 공통 상위 클래스.
 * INSERT 전용 테이블(orders, trade_history, withdrawal 등)이 쓴다. (테이블 정의서 공통 규칙 'JPA 매핑')
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseRegTimeEntity {

    @CreatedDate
    @Column(name = "reg_ts", nullable = false, updatable = false)
    private LocalDateTime regTs;
}
