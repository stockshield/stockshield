package com.stockshield.common.config;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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
        // DB TIMESTAMP는 마이크로초까지라, 엔티티에 남는 값과 저장된 값이 같도록 자름(같은 키 재요청 응답이 처음과 같아야 함)
        return () -> Optional.<TemporalAccessor>of(LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS));
    }
}
