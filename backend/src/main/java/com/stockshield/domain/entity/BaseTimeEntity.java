package com.stockshield.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.LocalDateTime;

/**
 * 등록일시(reg_ts) + 최종수정일시(mod_ts) 공통 상위 클래스 (JPA Auditing).
 * (member, account, orders)
 */
@Getter
@MappedSuperclass
public abstract class BaseTimeEntity extends BaseRegTimeEntity {

    @LastModifiedDate
    @Column(name = "mod_ts", nullable = false)
    private LocalDateTime modTs;
}

