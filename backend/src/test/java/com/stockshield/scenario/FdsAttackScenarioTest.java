package com.stockshield.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockshield.fds.risk.StockRiskJudge;
import com.stockshield.market.MarketTick;
import com.stockshield.market.MarketTickListener;
import com.stockshield.support.LedgerTestSupport;

/**
 * 윤제정 담당 FDS 공격 시나리오 6종 (SYS_DEV_001 #2, 합의서 12절 공격 시나리오 표).
 * 가짜 FDS 없이 실제 FDS로 돌고, ./gradlew test에서는 빠지고 ./gradlew fdsScenarioTest로만 돈다.
 * 테스트 메서드에 @Transactional을 붙이지 않는다(커밋 뒤 기록까지 실제와 같게 보려고).
 */
@Tag("fds-scenario")
class FdsAttackScenarioTest extends LedgerTestSupport {

    /** 가입 때 쓴 IP와 다른, 공격자가 쓰는 IP (문서용 대역 RFC 5737) */
    private static final String ATTACKER_IP = "198.51.100.23";
    /** 가짜 급등 시세를 넣을 종목: NAVER (다른 시나리오의 삼성전자와 겹치지 않게) */
    private static final String SURGE_STOCK = "035420";

    @Autowired
    private MarketTickListener tickListener;

    @Autowired
    private StringRedisTemplate redis;

    @AfterEach
    void clearSurge() {
        redis.delete(List.of(StockRiskJudge.RISK_KEY + SURGE_STOCK, "price:" + SURGE_STOCK));
    }

    private Map<String, Object> detection(String ruleCd, long mbrNo) {
        return db.queryForMap("""
                SELECT dtc_seq, rslt_cd, wdr_no FROM fds_detection
                WHERE mbr_no = ? AND rule_cd = ? ORDER BY dtc_seq DESC LIMIT 1
                """, mbrNo, ruleCd);
    }

    private int detectionCount(String ruleCd, long mbrNo) {
        return db.queryForObject("SELECT COUNT(*) FROM fds_detection WHERE mbr_no = ? AND rule_cd = ?",
                Integer.class, mbrNo, ruleCd);
    }

    private void surgeTick(LocalDateTime at, long price, String chgRt) {
        String prcTs = seoul(at).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        tickListener.handle(new MarketTick(1, "TICK", SURGE_STOCK, price, null, price - 250_000L,
                new BigDecimal(chgRt), 500L, 1_200_000L, prcTs, null, "TEST"));
    }

    // ------------------------------------------------------------------ ① 새 접속 로그인

    @Test
    @DisplayName("새 기기 로그인: 비밀번호가 맞아도 202이고 토큰을 주지 않으며, 탐지 기록은 PENDING")
    void newDeviceLogin() throws Exception {
        TestMember m = signUp();

        MvcResult r = mockMvc.perform(loginRequest(m.lgnId(), TEST_PASSWORD, newDeviceId(), ATTACKER_IP))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.authId").isNotEmpty())
                .andReturn();

        JsonNode b = body(r);
        assertThat(b.has("accessToken")).as("202 응답에 액세스 토큰이 없어야 함").isFalse();
        assertThat(b.has("refreshToken")).as("202 응답에 리프레시 토큰이 없어야 함").isFalse();
        assertThat(detection("LOGIN_NEW_ACCESS", m.mbrNo()).get("rslt_cd")).isEqualTo("PENDING");
        // 인증 전이므로 공격자 IP는 신뢰 접속에 들어가지 않음
        assertThat(db.queryForObject("SELECT COUNT(*) FROM trusted_access WHERE mbr_no = ? AND conn_ip = ?",
                Integer.class, m.mbrNo(), ATTACKER_IP)).isZero();
    }

    // ------------------------------------------------------------------ ② 대량 출금

    @Test
    @DisplayName("1회 대량 출금: 500만 원을 출금 비밀번호 없이 요청하면 428이고 탐지 기록은 PENDING, 예수금은 그대로")
    void singleLargeWithdrawal() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);

        mockMvc.perform(withdrawRequest(t, withdrawBody(5_000_000, newKey(), null)))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("WDR_PWD_REQUIRED"));

        assertThat(detection("WITHDRAW_LARGE", t.member().mbrNo()).get("rslt_cd")).isEqualTo("PENDING");
        assertThat(dpsBal(t.acntNo())).isEqualTo(PKG_10M);
        assertThat(withdrawalCount(t.acntNo())).isZero();
    }

    @Test
    @DisplayName("분할 대량 출금: 300만 원이 끝난 뒤 200만 원을 요청하면 24시간 누적 500만 원이라 428")
    void splitLargeWithdrawal() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);

        mockMvc.perform(withdrawRequest(t, withdrawBody(3_000_000, newKey(), null)))
                .andExpect(status().isCreated());
        mockMvc.perform(withdrawRequest(t, withdrawBody(2_000_000, newKey(), null)))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("WDR_PWD_REQUIRED"));

        assertThat(dpsBal(t.acntNo())).isEqualTo(PKG_10M - 3_000_000L).isEqualTo(expectedDpsBal(t.acntNo()));
        assertThat(withdrawalCount(t.acntNo())).isEqualTo(1);
        assertThat(detection("WITHDRAW_LARGE", t.member().mbrNo()).get("rslt_cd")).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("대량 출금 비밀번호 일치: 428 뒤 같은 멱등키에 맞는 비밀번호로 다시 보내면 201, 오류 횟수 0, 탐지 PASS와 출금번호 연결")
    void largeWithdrawalWithPassword() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        String key = newKey();

        mockMvc.perform(withdrawRequest(t, withdrawBody(5_000_000, key, null)))
                .andExpect(status().isPreconditionRequired());
        MvcResult done = mockMvc.perform(withdrawRequest(t, withdrawBody(5_000_000, key, TEST_WDR_PWD)))
                .andExpect(status().isCreated())
                .andReturn();
        String wdrNo = body(done).path("wdrNo").asText();

        assertThat(wdrNo).isNotBlank();
        assertThat(wdrPwdFailCnt(t.acntNo())).isZero();
        assertThat(dpsBal(t.acntNo())).isEqualTo(PKG_10M - 5_000_000L).isEqualTo(expectedDpsBal(t.acntNo()));
        Map<String, Object> dtc = detection("WITHDRAW_LARGE", t.member().mbrNo());
        assertThat(dtc.get("rslt_cd")).isEqualTo("PASS");
        assertThat(dtc.get("wdr_no")).isEqualTo(wdrNo);
        // 같은 요청을 다시 판단해 기록을 하나 더 만들지 않음
        assertThat(detectionCount("WITHDRAW_LARGE", t.member().mbrNo())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ ③ 새 IP 직후 거래

    @Test
    @DisplayName("새 IP 직후 거래: 새 IP에서 이메일 인증을 마치고 바로 주문하면 체결되고 TRADE_NEW_IP(NEW_IP_AFTER_AUTH)가 LOGGED로 남는다")
    void tradeRightAfterNewIp() throws Exception {
        Trader home = newTraderAt(MARKET_OPEN);
        TestMember m = home.member();
        String newDevice = newDeviceId();

        MvcResult accepted = mockMvc.perform(loginRequest(m.lgnId(), TEST_PASSWORD, newDevice, ATTACKER_IP))
                .andExpect(status().isAccepted())
                .andReturn();
        String authId = body(accepted).path("authId").asText();
        MvcResult verified = mockMvc.perform(verifyRequest(authId, fakeMail.lastCodeTo(m.emlAddr())))
                .andExpect(status().isOk())
                .andReturn();
        Tokens awayTokens = tokens(verified);

        putPriceNow(SAMSUNG, 70_000);
        // 새 IP·새 기기와 그 접속으로 받은 토큰으로 주문
        MvcResult filled = mockMvc.perform(post("/api/v1/orders").with(fromIp(ATTACKER_IP))
                        .header("Authorization", bearer(awayTokens))
                        .header("X-Device-Id", newDevice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(orderBody(SAMSUNG, "BUY", 1, newKey()))))
                .andExpect(status().isCreated())
                .andReturn();
        String ordNo = body(filled).path("ordNo").asText();
        assertThat(tradeCount(m.acntNo())).isEqualTo(1);

        // 커밋 뒤 별도 스레드에서 기록하므로 기다림. 주문 응답은 이 기록과 상관없이 이미 201
        String sql = "SELECT rslt_cd, ord_no, dtl_data->>'reason' AS reason FROM fds_detection "
                + "WHERE mbr_no = ? AND rule_cd = 'TRADE_NEW_IP'";
        await().atMost(Duration.ofSeconds(10))
                .until(() -> !db.queryForList(sql, m.mbrNo()).isEmpty());
        Map<String, Object> dtc = db.queryForMap(sql, m.mbrNo());
        assertThat(dtc.get("rslt_cd")).isEqualTo("LOGGED");
        assertThat(dtc.get("ord_no")).isEqualTo(ordNo);
        assertThat(dtc.get("reason")).isEqualTo("NEW_IP_AFTER_AUTH");

        // 가입한 IP의 거래는 기록하지 않음
        assertThat(order(home, SAMSUNG, "BUY", 1).getResponse().getStatus()).isEqualTo(201);
        await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(detectionCount("TRADE_NEW_IP", m.mbrNo())).isEqualTo(1));
    }

    // ------------------------------------------------------------------ ④ 가짜 급등 시세

    @Test
    @DisplayName("가짜 급등 시세: 전일 대비 +12% 시세가 들어오면 위험 의심 종목(risk-stocks)에 UP으로 표시된다")
    void fakeSurgeMarksRiskStock() throws Exception {
        testClock.set(MARKET_OPEN);
        mockMvc.perform(get("/api/v1/market/risk-stocks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.tckCd == '" + SURGE_STOCK + "')]").isEmpty());

        // 위험 의심 판단은 1분 수익률이 끝날 때(다음 분의 첫 시세가 올 때) 한다. 첫 분은 기준값이라
        // 10:00(기준) → 10:01(급등) → 10:02(10:01 수익률 확정·판단) 세 번 넣는다
        surgeTick(MARKET_OPEN.plusSeconds(30), 255_000L, "2.00");
        surgeTick(MARKET_OPEN.plusMinutes(1).plusSeconds(30), 280_000L, "12.00");
        testClock.set(MARKET_OPEN.plusMinutes(2));
        surgeTick(MARKET_OPEN.plusMinutes(2), 281_000L, "12.40");

        mockMvc.perform(get("/api/v1/market/risk-stocks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.tckCd == '" + SURGE_STOCK + "')].dir").value("UP"))
                .andExpect(jsonPath("$[?(@.tckCd == '" + SURGE_STOCK + "')].tckNm").value("NAVER"));
    }
}
