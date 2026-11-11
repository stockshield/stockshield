package com.stockshield.common.config;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * reg_ts·mod_ts를 DB 기본값이 아니라 Clock 빈으로 채운다.
 * (테이블 정의서 공통 규칙 '시각 기록', TRD_LED_001 구현방안)
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "clockDateTimeProvider")
public class JpaAuditingConfig {

    @Bean
    public DateTimeProvider clockDateTimeProvider(Clock clock) {
        return () -> Optional.<TemporalAccessor>of(LocalDateTime.now(clock));
    }
}
