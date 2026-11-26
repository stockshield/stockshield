package com.stockshield.common.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.LastModifiedDate;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

/**
 * 등록·수정 시각(reg_ts, mod_ts)이 있는 테이블의 공통 상위 클래스.
 * member, account, holding, fds_detection이 쓴다. (테이블 정의서 공통 규칙 'JPA 매핑')
 */
@Getter
@MappedSuperclass
public abstract class BaseTimeEntity extends BaseRegTimeEntity {

    @LastModifiedDate
    @Column(name = "mod_ts", nullable = false)
    private LocalDateTime modTs;
}
