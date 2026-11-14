package com.stockshield.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 테스트용 가짜 FdsService (연결 합의서 12절, 문서 수정 대기 목록 #57 ①).
 * 실제 FDS가 develop에 있으므로 이 가짜는 FDS 결과를 테스트가 정해야 하는 원장 동시성·멱등·롤백 테스트에만 @Import한다.
 * 공통 부모(IntegrationTestSupport)에는 넣지 않는다. @MockBean이 아니라 직접 만든 클래스를 빈으로 등록해
 * FdsService 인터페이스에 선언한 전파(MANDATORY·REQUIRES_NEW)가 가짜에도 그대로 걸린다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeFdsConfig {

    @Bean
    @Primary
    ScriptedFdsService scriptedFdsService() {
        return new ScriptedFdsService();
    }
}
