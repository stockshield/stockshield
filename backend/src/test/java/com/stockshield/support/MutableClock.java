package com.stockshield.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import com.stockshield.common.config.ClockConfig;

/**
 * 통합 테스트의 공용 Clock 빈(@Primary). 평소에는 실제 현재 시각을 돌려주고, 테스트가 시각을 정하면 그 시각에 멈춘다.
 * 장 운영 시간·5분 인증·30분 잠금·토큰 만료 판단과 reg_ts 기록이 모두 이 시계를 쓴다 (연결 합의서 12절, SYS_DEV_001 #3).
 * 클래스마다 다른 Clock 설정이나 @MockBean을 만들지 않는다(테스트 컨텍스트와 DB 연결 수가 늘어난다).
 * IntegrationTestSupport가 테스트마다 끝나면 실제 시각으로 되돌린다.
 */
public class MutableClock extends Clock {

    private volatile Instant fixed;

    /** 이 시각(Asia/Seoul)에 멈춘다 */
    public void set(LocalDateTime seoulTime) {
        this.fixed = seoulTime.atZone(ClockConfig.ZONE).toInstant();
    }

    public void advance(Duration d) {
        Instant base = fixed == null ? Instant.now() : fixed;
        this.fixed = base.plus(d);
    }

    /** 실제 현재 시각으로 되돌린다 */
    public void reset() {
        this.fixed = null;
    }

    public LocalDateTime now() {
        return LocalDateTime.ofInstant(instant(), ClockConfig.ZONE);
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
        Instant f = fixed;
        return f == null ? Instant.now() : f;
    }
}
