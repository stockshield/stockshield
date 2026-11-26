package com.stockshield.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * 실제 Tomcat을 임의 포트로 띄우는 통합 테스트 (P11 X-Forwarded-For, P12 STOMP 연결).
 * MockMvc는 Tomcat을 거치지 않아 신뢰 프록시 설정과 WebSocket 연결을 확인할 수 없음.
 * 이 클래스를 상속한 테스트끼리는 컨텍스트 하나를 같이 씀.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// PC의 infra/.env 값과 관계없이 WebSocket 허용 Origin을 고정하려고 둠
@TestPropertySource(properties = "stockshield.cors.allowed-origins=" + RealServerTestSupport.TEST_ORIGIN)
public abstract class RealServerTestSupport extends IntegrationTestSupport {

    protected static final String TEST_ORIGIN = "http://localhost:5173";

    @LocalServerPort
    protected int port;
}
