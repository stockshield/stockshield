package com.stockshield.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TRD_ORD_001 #2: 휴장일을 뺀 평일 09:00:00 이상 15:30:00 미만, 시연 모드면 검사 생략 */
class MarketHoursTest {

    private final MarketHours hours = new MarketHours(false, "2026-12-25, 2026-12-31");

    @Test
    @DisplayName("평일 09:00:00부터 열리고 15:30:00 정각부터 닫힌다")
    void weekdayBoundary() {
        assertThat(hours.isOpen(LocalDateTime.of(2026, 11, 16, 8, 59, 59))).isFalse();
        assertThat(hours.isOpen(LocalDateTime.of(2026, 11, 16, 9, 0, 0))).isTrue();
        assertThat(hours.isOpen(LocalDateTime.of(2026, 11, 16, 15, 29, 59))).isTrue();
        assertThat(hours.isOpen(LocalDateTime.of(2026, 11, 16, 15, 30, 0))).isFalse();
    }

    @Test
    @DisplayName("주말과 휴장일은 장중 시각이어도 닫혀 있다")
    void weekendAndHoliday() {
        assertThat(hours.isOpen(LocalDateTime.of(2026, 11, 14, 10, 0))).isFalse();   // 토
        assertThat(hours.isOpen(LocalDateTime.of(2026, 11, 15, 10, 0))).isFalse();   // 일
        assertThat(hours.isOpen(LocalDateTime.of(2026, 12, 25, 10, 0))).isFalse();   // 금, 성탄절
        assertThat(hours.isOpen(LocalDateTime.of(2026, 12, 31, 10, 0))).isFalse();   // 목, 연말 휴장
        assertThat(hours.isOpen(LocalDateTime.of(2026, 12, 24, 10, 0))).isTrue();
    }

    @Test
    @DisplayName("시연 모드에서는 주말·장 마감 뒤에도 열려 있다")
    void demoMode() {
        MarketHours demo = new MarketHours(true, "2026-12-25");
        assertThat(demo.isOpen(LocalDateTime.of(2026, 11, 14, 23, 0))).isTrue();
        assertThat(demo.isOpen(LocalDateTime.of(2026, 12, 25, 10, 0))).isTrue();
    }

    @Test
    @DisplayName("휴장일이 비어 있으면 평일 규칙만 쓰고, 형식이 틀리면 만들 때 실패한다")
    void holidayConfig() {
        assertThat(new MarketHours(false, "").isOpen(LocalDateTime.of(2026, 12, 25, 10, 0))).isTrue();
        assertThatThrownBy(() -> new MarketHours(false, "2026/12/25")).isInstanceOf(DateTimeParseException.class);
    }
}
