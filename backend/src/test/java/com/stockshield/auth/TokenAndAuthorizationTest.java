package com.stockshield.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Map;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.stockshield.support.IntegrationTestSupport;

/**
 * 토큰 재발급·로그아웃, URL 권한(401·403), /members/me, CORS.
 * USR_AUTH_002 #1·#4, USR_AUTH_003 #1·#2·#4, API 명세서 'API_명세서' 8·9행·'공통 규칙' 19·20·58행,
 * 문서 수정 대기 목록 #6·#9, 연결 합의서 3절(CORS)·8절(권한 경로)
 */
class TokenAndAuthorizationTest extends IntegrationTestSupport {

    static final String FRONT = "http://localhost:5173";

    @Test
    @DisplayName("재발급: 살아 있는 Refresh Token이면 새 Access Token, 조작·만료·Access Token을 넣으면 401")
    void refresh() throws Exception {
        TestMember m = signUp();
        Tokens t = login(m);

        mockMvc.perform(refreshRequest(t.refreshToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        // 서명 끝에 글자를 덧붙이는 것은 Base64 디코딩 결과가 같아질 수 있어 조작이 아니다. 내용(payload)을 바꾼다
        mockMvc.perform(refreshRequest(tamperPayload(t.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(refreshRequest(t.accessToken())).andExpect(status().isUnauthorized());

        testClock.advance(JwtProvider.REFRESH_TTL.plus(Duration.ofSeconds(1)));
        mockMvc.perform(refreshRequest(t.refreshToken())).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로그아웃하면 그 Refresh Token으로 재발급할 수 없다. 로그아웃은 로그인한 사용자만 부를 수 있다")
    void logoutRevokesRefreshToken() throws Exception {
        TestMember m = signUp();
        Tokens t = login(m);

        mockMvc.perform(logoutRequest(t.refreshToken())).andExpect(status().isUnauthorized());
        mockMvc.perform(logoutRequest(t.refreshToken()).header("Authorization", bearer(t)))
                .andExpect(status().isOk());
        mockMvc.perform(refreshRequest(t.refreshToken())).andExpect(status().isUnauthorized());

        // 이미 지운 토큰을 다시 보내도 200
        mockMvc.perform(logoutRequest(t.refreshToken()).header("Authorization", bearer(t))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("다른 사람의 Refresh Token은 로그아웃으로 지울 수 없다")
    void cannotLogoutOthersToken() throws Exception {
        Tokens mine = login(signUp());
        Tokens other = login(signUp());

        mockMvc.perform(logoutRequest(other.refreshToken()).header("Authorization", bearer(mine)))
                .andExpect(status().isOk());
        mockMvc.perform(refreshRequest(other.refreshToken())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /members/me: 일반 사용자·관리자 모두 {lgnId, mbrNm, roleCd}, 토큰이 없으면 401")
    void me() throws Exception {
        TestMember user = signUp();
        mockMvc.perform(get("/api/v1/members/me").header("Authorization", bearer(login(user))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lgnId").value(user.lgnId()))
                .andExpect(jsonPath("$.mbrNm").value("테스트회원"))
                .andExpect(jsonPath("$.roleCd").value("ROLE_USER"));

        TestMember admin = createAdmin();
        mockMvc.perform(get("/api/v1/members/me").header("Authorization", bearer(loginAsAdmin(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lgnId").value(admin.lgnId()))
                .andExpect(jsonPath("$.roleCd").value("ROLE_ADMIN"));

        mockMvc.perform(get("/api/v1/members/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    @DisplayName("관리자 API는 ROLE_ADMIN만: 토큰 없음 401, 일반 사용자 403 ACCESS_DENIED, 관리자는 통과")
    void adminApiRequiresAdmin() throws Exception {
        String adminApi = "/api/v1/admin/detections/999999999/ai-reason";
        Tokens user = login(signUp());
        Tokens admin = loginAsAdmin(createAdmin());

        mockMvc.perform(post(adminApi)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(adminApi).header("Authorization", bearer(user)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        // 권한 검사는 통과하고 그 API가 없는 탐지 기록을 404로 알린다
        mockMvc.perform(post(adminApi).header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DETECTION_NOT_FOUND"));
    }

    @Test
    @DisplayName("주문·계좌·출금은 ROLE_USER만: 관리자는 403(직무 분리), 일반 사용자는 권한 검사를 통과한다")
    void tradingApisRequireUser() throws Exception {
        Tokens user = login(signUp());
        Tokens admin = loginAsAdmin(createAdmin());

        for (MockHttpServletRequestBuilder req : new MockHttpServletRequestBuilder[] {
                post("/api/v1/orders"), get("/api/v1/accounts/me/summary"), post("/api/v1/accounts/me/withdrawals")}) {
            mockMvc.perform(req).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/v1/orders").header("Authorization", bearer(admin)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(get("/api/v1/accounts/me/summary").header("Authorization", bearer(admin)))
                .andExpect(status().isForbidden());
        // 주문·계좌 API는 PR ②에서 들어온다. 지금은 권한 검사를 통과해 404가 된다
        mockMvc.perform(get("/api/v1/accounts/me/summary").header("Authorization", bearer(user)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("만료된 Access Token은 401, 시세 조회는 토큰 없이 된다, 표에 없는 /api/** 는 로그인해야 한다")
    void tokenExpiryAndPublicEndpoints() throws Exception {
        Tokens user = login(signUp());
        testClock.advance(JwtProvider.ACCESS_TTL.plusSeconds(1));
        mockMvc.perform(get("/api/v1/members/me").header("Authorization", bearer(user)))
                .andExpect(status().isUnauthorized());
        testClock.reset();

        mockMvc.perform(get("/api/v1/market/stocks/search").param("keyword", "삼성")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/unknown-api")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/unknown-api").header("Authorization", bearer(user)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("CORS: 허용 출처의 사전 요청은 Authorization·Content-Type·X-Device-Id를 허용하고, 응답은 X-Trace-Id 등을 노출한다")
    void cors() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", FRONT)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Authorization, Content-Type, X-Device-Id"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", FRONT))
                .andExpect(header().string("Access-Control-Allow-Headers",
                        Matchers.allOf(Matchers.containsStringIgnoringCase("authorization"),
                                Matchers.containsStringIgnoringCase("content-type"),
                                Matchers.containsStringIgnoringCase("x-device-id"))));

        mockMvc.perform(get("/api/v1/market/stocks/search").param("keyword", "삼성").header("Origin", FRONT))
                .andExpect(header().string("Access-Control-Expose-Headers",
                        Matchers.allOf(Matchers.containsString("X-Trace-Id"),
                                Matchers.containsString("Idempotency-Replayed"),
                                Matchers.containsString("Retry-After"))));

        mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    /** 토큰의 내용(가운데 부분) 한 글자를 바꾼다. 서명이 내용과 맞지 않게 된다 */
    private static String tamperPayload(String jwt) {
        String[] p = jwt.split("\\.");
        char c = p[1].charAt(5);
        String payload = p[1].substring(0, 5) + (c == 'A' ? 'B' : 'A') + p[1].substring(6);
        return p[0] + "." + payload + "." + p[2];
    }

    private MockHttpServletRequestBuilder refreshRequest(String refreshToken) throws Exception {
        return post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("refreshToken", refreshToken)));
    }

    private MockHttpServletRequestBuilder logoutRequest(String refreshToken) throws Exception {
        return post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("refreshToken", refreshToken)));
    }
}
