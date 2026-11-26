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

import com.stockshield.support.RealServerTestSupport;

/**
 * 운영 설정은 nginx(172.28.0.10)만 신뢰 프록시로 둬, 그 밖에서 온 X-Forwarded-For는 무시함
 * (P11, SYS_SEC_001 #4, 연결 합의서 2절 규칙 9, 대기 목록 #37·#38).
 * application-prod.yml을 그대로 읽으려고 prod 프로필로 띄움. 관리자 시드 검사(adminRequired)는 IntegrationTestSupport가 끔.
 * internal-proxies를 빼면 Boot 기본값(사설 대역·127.x)이 127.0.0.1을 믿어 이 테스트가 실패함.
 */
@ActiveProfiles("prod")
class ForwardedForProdProxyTest extends RealServerTestSupport {

    private static final String SPOOFED_IP = "203.0.113.7";

    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("운영 설정에서 nginx가 아닌 곳이 보낸 X-Forwarded-For는 무시하고 실제 접속 주소로 판단·기록한다")
    void forwardedForFromNonNginxPeerIsIgnored() throws Exception {
        TestMember m = signUp();   // 127.0.0.1과 이 기기를 신뢰 접속으로 등록

        HttpResponse<String> response = loginOverHttp(m, SPOOFED_IP);

        // 헤더를 믿었다면 처음 보는 IP라 202(새 접속 인증)
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForList("SELECT conn_ip FROM login_history WHERE lgn_id = ?", String.class,
                m.lgnId())).containsExactly("127.0.0.1");
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
