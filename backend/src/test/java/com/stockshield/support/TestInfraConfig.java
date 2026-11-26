package com.stockshield.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 모든 통합 테스트가 함께 쓰는 테스트 빈: 공용 Clock과 가짜 메일 발송기. IntegrationTestSupport가 @Import한다.
 * 가짜 FdsService는 여기에 넣지 않는다(FakeFdsConfig, 필요한 테스트만 @Import, 연결 합의서 12절).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestInfraConfig {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    FakeAuthCodeMailSender fakeAuthCodeMailSender() {
        return new FakeAuthCodeMailSender();
    }
}
