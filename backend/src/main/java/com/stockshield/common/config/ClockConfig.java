package com.stockshield.common.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 현재 시각은 이 Clock 빈에서만 얻는다.
 * 장 운영 시간·24시간 누적 판단과 거래 시각 기록(reg_ts)이 같은 시계를 쓰고,
 * 테스트에서는 고정 시계로 바꿔 같은 결과를 재현한다. (TRD_ORD_001 구현방안, SYS_DEV_001 #3, 부록 #17)
 */
@Configuration
public class ClockConfig {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(ZONE);
    }
}
