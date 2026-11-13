package com.stockshield.fds;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import com.stockshield.common.config.ClockConfig;

/**
 * FDS 테스트가 움직이는 시계 (Asia/Seoul).
 * 실제 현재 시각에서 시작해, 1분마다 도는 만료 정리 작업(실제 시계)과 어긋나지 않게 한다.
 * 컨텍스트의 Clock 빈을 바꾸지 않고, 테스트가 직접 만든 FDS 컴포넌트에만 넣는다(테스트 컨텍스트를 늘리지 않기 위해).
 */
final class TestClock extends Clock {

    private Instant instant;

    TestClock() {
        this.instant = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    void advance(Duration d) {
        instant = instant.plus(d);
    }

    LocalDateTime now() {
        return LocalDateTime.now(this);
    }

    @Override
    public ZoneId getZone() {
        return ClockConfig.ZONE;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
