package com.stockshield.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 등록일시(reg_ts)만 보유하는 테이블용 공통 상위 클래스.
 * (login_history, stock_master, price_minute, news, news_stock, fds_result, trade_history, fds_action_log, reconcile_result)
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseRegTimeEntity {

    @CreatedDate
    @Column(name = "reg_ts", nullable = false, updatable = false)
    private LocalDateTime regTs;
}

