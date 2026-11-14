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

import com.stockshield.support.RealServerTestSupport;

/**
 * 신뢰 프록시를 설정하지 않은 기본 상태에서는 X-Forwarded-For를 읽지 않음 (SYS_SEC_001 #4, 연결 합의서 2절 규칙 9, 대기 목록 #37·#38).
 * RequestMetasTest는 RequestMetas만 보고, 이 테스트는 실제 Tomcat 설정(server.forward-headers-strategy)까지 봄.
 * 그 설정을 native로 켜면 Tomcat이 127.0.0.1을 사설 대역 기본값으로 믿고 접속 주소를 헤더 값으로 바꿔 이 테스트가 실패함.
 */
class ForwardedForIgnoredTest extends RealServerTestSupport {

    private static final String SPOOFED_IP = "203.0.113.7";

    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("직접 붙은 클라이언트가 보낸 X-Forwarded-For는 무시하고 실제 접속 주소로 판단·기록한다")
    void forwardedForFromUntrustedPeerIsIgnored() throws Exception {
        TestMember m = signUp();   // 127.0.0.1과 이 기기를 신뢰 접속으로 등록

        HttpRequest login = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .header("X-Device-Id", m.deviceId())
                .header("X-Forwarded-For", SPOOFED_IP)
                .POST(HttpRequest.BodyPublishers.ofString(json(Map.of("lgnId", m.lgnId(), "lgnPwd", TEST_PASSWORD))))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(login, HttpResponse.BodyHandlers.ofString());

        // 헤더를 믿었다면 처음 보는 IP라 202(새 접속 인증)
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForList("SELECT conn_ip FROM login_history WHERE lgn_id = ?", String.class,
                m.lgnId())).containsExactly("127.0.0.1");
    }
}
