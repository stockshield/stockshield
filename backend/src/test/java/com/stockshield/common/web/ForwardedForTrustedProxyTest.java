package com.stockshield.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.stockshield.support.RealServerTestSupport;

/**
 * 신뢰 프록시가 보낸 X-Forwarded-For는 사용자 IP로 판단·기록함 (P11, SYS_SEC_001 #4, 연결 합의서 2절 규칙 9, 대기 목록 #38).
 * 테스트는 172.28.0.10에서 보낼 수 없어, prod 설정 위에 신뢰 주소만 127.0.0.1로 바꿔 nginx 자리에 둠.
 * forward-headers-strategy=native를 빼면 internal-proxies가 있어도 Tomcat이 헤더를 처리하지 않아 이 테스트가 실패함.
 */
@ActiveProfiles("prod")
class ForwardedForTrustedProxyTest extends RealServerTestSupport {

    private static final String CLIENT_IP = "203.0.113.7";

    @Autowired JdbcTemplate jdbcTemplate;

    // @TestPropertySource 인라인 값은 properties 문법이라 역슬래시가 사라져서 이쪽을 씀
    @DynamicPropertySource
    static void trustLoopbackAsProxy(DynamicPropertyRegistry registry) {
        registry.add("server.tomcat.remoteip.internal-proxies", () -> "127\\.0\\.0\\.1");
    }

    @Test
    @DisplayName("신뢰 프록시가 넘긴 X-Forwarded-For의 주소로 새 접속을 판단하고 로그인 이력·탐지 기록에 남긴다")
    void forwardedForFromTrustedProxyIsUsed() throws Exception {
        TestMember m = signUp();   // 127.0.0.1(프록시 자리)과 이 기기를 신뢰 접속으로 등록

        HttpResponse<String> response = loginOverHttp(m, CLIENT_IP);

        // 헤더를 무시했다면 신뢰 접속(127.0.0.1)이라 200
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(jdbcTemplate.queryForList("SELECT conn_ip FROM login_history WHERE lgn_id = ?", String.class,
                m.lgnId())).containsExactly(CLIENT_IP);
        assertThat(jdbcTemplate.queryForList(
                "SELECT conn_ip FROM fds_detection WHERE mbr_no = ? AND rule_cd = 'LOGIN_NEW_ACCESS'", String.class,
                m.mbrNo())).containsExactly(CLIENT_IP);
    }

    private HttpResponse<String> loginOverHttp(TestMember m, String forwardedFor) throws Exception {
        HttpRequest login = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .header("X-Device-Id", m.deviceId())
                .header("X-Forwarded-For", forwardedFor)
                .POST(HttpRequest.BodyPublishers.ofString(json(Map.of("lgnId", m.lgnId(), "lgnPwd", TEST_PASSWORD))))
                .build();
        return HttpClient.newHttpClient().send(login, HttpResponse.BodyHandlers.ofString());
    }
}
