package com.stockshield.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 실제 PostgreSQL 15.19·Redis 7.2.17 컨테이너로 검증하는 통합 테스트의 공통 설정 (연결 합의서 12절, 문서 수정 대기 목록 #58).
 * 운영과 같이 마이그레이션은 소유자 계정(컨테이너 기본 계정)으로, 애플리케이션은 앱 계정으로 접속한다.
 * 컨테이너는 테스트 전체에서 한 번만 띄운다.
 *
 * - 테스트 메서드에 @Transactional을 붙이지 않는다. 커밋·롤백·락을 실제와 같게 확인하기 위해서다.
 * - 테스트는 데이터를 지우지 않는다(앱 계정에 DELETE 권한이 없다). 테스트마다 새 회원·계좌·멱등키를 만들고 자기 데이터로 확인한다.
 * - @Scheduled 작업과 Kafka 리스너는 끈다. 스케줄 작업은 테스트가 메서드를 직접 부른다.
 *   AI 서버 호출은 src/test/resources/config/application.properties가 끈다(stockshield.ai.enabled=false).
 * - 컨텍스트가 여러 개 생겨도 연결이 모자라지 않게 PostgreSQL max_connections=300, Hikari minimum-idle=2.
 * - 공용 Clock(MutableClock)과 가짜 메일 발송기는 TestInfraConfig가 @Primary로 넣고, 테스트가 끝날 때마다 되돌린다.
 * - 가짜 FdsService는 여기에 넣지 않는다. 필요한 테스트만 FakeFdsConfig를 @Import한다.
 */
@SpringBootTest(properties = {
        "stockshield.scheduling.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.datasource.hikari.minimum-idle=2",
        "stockshield.auth.jwt-secret=" + WebMvcSecurityTestConfig.TEST_JWT_SECRET,
        // 내부 대사 확인 API용. 빈 토큰의 401은 InternalTokenVerifierTest가 컨텍스트 없이 확인함(컨텍스트를 늘리지 않으려고)
        "stockshield.internal-token=" + IntegrationTestSupport.TEST_INTERNAL_TOKEN,
        // PC의 infra/.env 값이 테스트에 들어오지 않게 한다(CI에는 .env가 없다). 관리자 시드는 AdminSeedMigrationTest가 따로 확인한다
        "spring.flyway.placeholders.adminLgnId=",
        "spring.flyway.placeholders.adminEmlAddr=",
        "spring.flyway.placeholders.adminPwdHash=",
        "spring.flyway.placeholders.adminRequired=false",
        "stockshield.demo-mode=false"
})
@AutoConfigureMockMvc
@Import(TestInfraConfig.class)
public abstract class IntegrationTestSupport {

    protected static final String APP_DB_USER = "stockshield_app";
    protected static final String APP_DB_PASSWORD = "app-test-password";
    /** 테스트 회원의 로그인 비밀번호(영문·숫자·특수문자) */
    protected static final String TEST_PASSWORD = "Test1234!";
    protected static final String TEST_WDR_PWD = "246810";
    protected static final String TEST_INTERNAL_TOKEN = "test-internal-token-0123456789";
    /** MockMvc 요청의 기본 접속 IP */
    protected static final String DEFAULT_IP = "127.0.0.1";

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:15.19"))
                    .withEnv("TZ", "Asia/Seoul")
                    .withEnv("PGTZ", "Asia/Seoul")
                    .withCommand("postgres", "-c", "max_connections=300", "-c", "fsync=off");

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.2.17")).withExposedPorts(6379);

    static {
        Startables.deepStart(POSTGRES, REDIS).join();
        // 공용 Redis 주소는 시스템 속성으로 넣는다. @DynamicPropertySource보다 우선순위가 낮아,
        // 자기 클래스 전용 Redis를 쓰는 테스트(시세 테스트, Redis 장애 테스트)가 @DynamicPropertySource로 덮어쓸 수 있다.
        // 부모와 자식의 @DynamicPropertySource가 같은 키를 쓰면 어느 쪽이 이길지 호출 순서에 달려 있어 쓰지 않는다.
        System.setProperty("spring.data.redis.host", REDIS.getHost());
        System.setProperty("spring.data.redis.port", String.valueOf(REDIS.getMappedPort(6379)));
        System.setProperty("spring.data.redis.password", "");
    }

    @DynamicPropertySource
    static void sharedContainerProperties(DynamicPropertyRegistry registry) {
        // 애플리케이션: 앱 계정
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_DB_USER);
        registry.add("spring.datasource.password", () -> APP_DB_PASSWORD);
        // 마이그레이션: 소유자 계정
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.appDbUser", () -> APP_DB_USER);
        registry.add("spring.flyway.placeholders.appDbPassword", () -> APP_DB_PASSWORD);
        // 공용 Redis는 위 static 블록의 시스템 속성. 장애를 일으키는 테스트는 자기 클래스 전용 Redis를 따로 띄운다(공용 컨테이너는 멈추지 않는다)
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected MutableClock testClock;

    @Autowired
    protected FakeAuthCodeMailSender fakeMail;

    @Autowired
    protected ObjectMapper testJson;

    @Autowired
    private JdbcTemplate supportJdbc;

    @Autowired
    private PasswordEncoder supportPasswordEncoder;

    @Autowired
    private StringRedisTemplate supportRedis;

    @AfterEach
    void resetSharedTestDoubles() {
        testClock.reset();
        fakeMail.reset();
    }

    // ------------------------------------------------------------------ 가입·로그인·토큰 도우미

    /** 가입한 회원과 가입에 쓴 IP·기기(이 IP·기기로 로그인하면 KNOWN). 관리자는 acntNo가 null */
    protected record TestMember(long mbrNo, String acntNo, String lgnId, String emlAddr, String deviceId, String ip) {
    }

    /** 로그인·인증 확인으로 받은 토큰. Redis 장애 로그인이면 refreshToken이 null */
    protected record Tokens(String accessToken, String refreshToken, String roleCd) {
    }

    protected static String newDeviceId() {
        return UUID.randomUUID().toString();
    }

    /** 영문 소문자로 시작하는 12자 아이디 (^[a-z][a-z0-9]{3,15}$) */
    protected static String uniqueLgnId() {
        return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
    }

    protected static String emailOf(String lgnId) {
        return lgnId + "@test.stockshield.dev";
    }

    /** 요청의 접속 IP를 바꾼다 (새 IP는 remoteAddr로 만든다, 합의서 12절) */
    protected static RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    /** 가입 인증 코드를 받아 authId를 돌려준다. 코드는 fakeMail.lastCodeTo(email) */
    protected String requestSignupCode(String emlAddr) throws Exception {
        MvcResult r = mockMvc.perform(emailCodeRequest(emlAddr))
                .andExpect(status().isOk())
                .andReturn();
        return body(r).path("authId").asText();
    }

    protected MockHttpServletRequestBuilder emailCodeRequest(String emlAddr) throws Exception {
        return post("/api/v1/members/email-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("emlAddr", emlAddr)));
    }

    /** 가입 요청 본문(모두 올바른 값). 테스트가 일부 값을 바꿔 쓴다 */
    protected Map<String, Object> signupBody(String lgnId, String emlAddr, String authId, String authCode) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("lgnId", lgnId);
        b.put("lgnPwd", TEST_PASSWORD);
        b.put("mbrNm", "테스트회원");
        b.put("emlAddr", emlAddr);
        b.put("authId", authId);
        b.put("authCode", authCode);
        b.put("wdrPwd", TEST_WDR_PWD);
        b.put("initPkgCd", "PKG_10M");
        return b;
    }

    protected MockHttpServletRequestBuilder signupRequest(Map<String, Object> body, String deviceId, String ip)
            throws Exception {
        MockHttpServletRequestBuilder b = post("/api/v1/members").with(fromIp(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body));
        return deviceId == null ? b : b.header("X-Device-Id", deviceId);
    }

    /** 새 회원을 기본 IP·새 기기로 가입시킨다 */
    protected TestMember signUp() throws Exception {
        return signUp(DEFAULT_IP, newDeviceId(), "PKG_10M");
    }

    protected TestMember signUp(String ip, String deviceId, String initPkgCd) throws Exception {
        String lgnId = uniqueLgnId();
        String email = emailOf(lgnId);
        String authId = requestSignupCode(email);
        Map<String, Object> b = signupBody(lgnId, email, authId, fakeMail.lastCodeTo(email));
        b.put("initPkgCd", initPkgCd);
        MvcResult r = mockMvc.perform(signupRequest(b, deviceId, ip))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = body(r);
        return new TestMember(body.path("mbrNo").asLong(), body.path("acntNo").asText(), lgnId, email, deviceId, ip);
    }

    /** 가입한 IP·기기로 로그인한다(실제 FDS가 KNOWN으로 판단해 바로 200) */
    protected Tokens login(TestMember m) throws Exception {
        MvcResult r = mockMvc.perform(loginRequest(m.lgnId(), TEST_PASSWORD, m.deviceId(), m.ip()))
                .andExpect(status().isOk())
                .andReturn();
        return tokens(r);
    }

    protected MockHttpServletRequestBuilder loginRequest(String lgnId, String lgnPwd, String deviceId, String ip)
            throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("lgnId", lgnId);
        b.put("lgnPwd", lgnPwd);
        MockHttpServletRequestBuilder req = post("/api/v1/auth/login").with(fromIp(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(b));
        return deviceId == null ? req : req.header("X-Device-Id", deviceId);
    }

    /** 새 접속 인증 코드 확인 요청 */
    protected MockHttpServletRequestBuilder verifyRequest(String authId, String authCode) throws Exception {
        return post("/api/v1/auth/email-verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("authId", authId, "authCode", authCode)));
    }

    /**
     * 최초 관리자 계정을 DB에 바로 만든다. 테스트 DB에서는 V4가 아무것도 넣지 않는다(ADMIN_* 값이 비어 있음).
     * V4와 같이 BCrypt($2a$10$) 해시·ROLE_ADMIN·계좌와 신뢰 접속 없음.
     */
    protected TestMember createAdmin() {
        String lgnId = "a" + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
        String email = emailOf(lgnId);
        Long mbrNo = supportJdbc.queryForObject(
                "INSERT INTO member (lgn_id, lgn_pwd, mbr_nm, eml_addr, role_cd) VALUES (?, ?, '관리자', ?, 'ROLE_ADMIN') "
                        + "RETURNING mbr_no",
                Long.class, lgnId, supportPasswordEncoder.encode(TEST_PASSWORD), email);
        return new TestMember(mbrNo, null, lgnId, email, newDeviceId(), DEFAULT_IP);
    }

    /** 관리자 첫 로그인: 신뢰 접속이 없어 새 접속 인증(202)을 거쳐 토큰을 받는다 */
    protected Tokens loginAsAdmin(TestMember admin) throws Exception {
        MvcResult accepted = mockMvc.perform(loginRequest(admin.lgnId(), TEST_PASSWORD, admin.deviceId(), admin.ip()))
                .andExpect(status().isAccepted())
                .andReturn();
        String authId = body(accepted).path("authId").asText();
        MvcResult r = mockMvc.perform(verifyRequest(authId, fakeMail.lastCodeTo(admin.emlAddr())))
                .andExpect(status().isOk())
                .andReturn();
        return tokens(r);
    }

    protected static String bearer(Tokens t) {
        return "Bearer " + t.accessToken();
    }

    // ------------------------------------------------------------------ 시세 도우미

    /** price:{tckCd}에 시세를 넣는다. 시세 컨슈머(윤제정)와 같은 형식 (합의서 9절) */
    protected void putPrice(String tckCd, long currentPrice, OffsetDateTime prcTs) throws Exception {
        supportRedis.opsForValue().set("price:" + tckCd,
                json(Map.of("tckCd", tckCd, "currentPrice", currentPrice, "prcTs", prcTs.toString())));
    }

    // ------------------------------------------------------------------ JSON

    protected String json(Object value) throws Exception {
        return testJson.writeValueAsString(value);
    }

    protected JsonNode body(MvcResult r) throws Exception {
        return testJson.readTree(r.getResponse().getContentAsString());
    }

    protected Tokens tokens(MvcResult r) throws Exception {
        JsonNode b = body(r);
        return new Tokens(b.path("accessToken").asText(),
                b.path("refreshToken").isNull() ? null : b.path("refreshToken").asText(), b.path("roleCd").asText());
    }
}
