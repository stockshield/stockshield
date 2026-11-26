package com.stockshield.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import com.stockshield.support.LedgerTestSupport;

/**
 * 조성우 담당 공격 시나리오 4종 (SYS_DEV_001 #2, 합의서 12절 공격 시나리오 표).
 * 가짜 FDS 없이 실제 FDS로 돌고, ./gradlew test에서는 빠지고 ./gradlew fdsScenarioTest로만 돈다.
 * 테스트 메서드에 @Transactional을 붙이지 않는다(커밋·락·커밋 뒤 기록을 실제와 같게 보려고).
 */
@Tag("fds-scenario")
class LedgerAttackScenarioTest extends LedgerTestSupport {

    private static final String WRONG_PWD = "000000";
    // 신뢰 접속에 없는 IP. 이메일 인증으로 등록한 IP는 첫 인증 24시간 기준이 실제 시각과 고정 Clock 차이에 흔들려서 쓰지 않음
    private static final String UNTRUSTED_IP = "203.0.113.77";

    private static long count(List<MvcResult> results, int status) {
        return results.stream().filter(r -> r.getResponse().getStatus() == status).count();
    }

    @Test
    @DisplayName("인증 코드 5회 오류: 새 기기 로그인(202) 뒤 틀린 코드 400 4회, 5회째 410이고 탐지 기록은 FAIL")
    void fiveWrongAuthCodes() throws Exception {
        TestMember m = signUp();
        MvcResult accepted = mockMvc.perform(loginRequest(m.lgnId(), TEST_PASSWORD, newDeviceId(), m.ip()))
                .andExpect(status().isAccepted())
                .andReturn();
        String authId = body(accepted).path("authId").asText();
        String code = fakeMail.lastCodeTo(m.emlAddr());
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int remain = 4; remain >= 1; remain--) {
            mockMvc.perform(verifyRequest(authId, wrong))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("AUTH_CODE_MISMATCH"))
                    .andExpect(jsonPath("$.detail.remainCnt").value(remain));
        }
        mockMvc.perform(verifyRequest(authId, wrong))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("AUTH_CODE_EXPIRED"));

        assertThat(db.queryForList(
                "SELECT rslt_cd FROM fds_detection WHERE mbr_no = ? AND rule_cd = 'LOGIN_NEW_ACCESS'",
                String.class, m.mbrNo())).containsExactly("FAIL");
        // 맞는 코드도 더는 받지 않고 새 기기는 신뢰 접속에 들어가지 않음
        mockMvc.perform(verifyRequest(authId, code)).andExpect(status().isGone());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM trusted_access WHERE mbr_no = ?", Integer.class,
                m.mbrNo())).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 분할 출금: 300만 원 2건을 서로 다른 키로 동시에 보내면 1건 201, 1건 428이고 예수금은 300만 원만 줄어든다")
    void concurrentSplitWithdrawals() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);

        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            calls.add(() -> mockMvc.perform(withdrawRequest(t, withdrawBody(3_000_000, newKey(), null))).andReturn());
        }
        List<MvcResult> results = concurrently(calls);

        assertThat(count(results, 201)).isEqualTo(1);
        assertThat(count(results, 428)).isEqualTo(1);
        assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 428).map(LedgerTestSupport::content))
                .allSatisfy(b -> assertThat(b).contains("WDR_PWD_REQUIRED"));
        assertThat(dpsBal(t.acntNo())).isEqualTo(PKG_10M - 3_000_000L).isEqualTo(expectedDpsBal(t.acntNo()));
        assertThat(withdrawalCount(t.acntNo())).isEqualTo(1);
    }

    @Test
    @DisplayName("출금 비밀번호 5회 오류: 422 4회 뒤 423. 계좌 TEMP_LOCKED·거절 출금·TEMP_LOCK 이력·LOCKED 탐지가 한 커밋으로 남는다")
    void fiveWrongWithdrawPasswords() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);

        for (int attempt = 1; attempt <= 4; attempt++) {
            mockMvc.perform(withdrawRequest(t, withdrawBody(5_000_000, newKey(), WRONG_PWD)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("WDR_PWD_MISMATCH"))
                    .andExpect(jsonPath("$.detail.remainCnt").value(5 - attempt));
        }
        MvcResult locked = mockMvc.perform(withdrawRequest(t, withdrawBody(5_000_000, newKey(), WRONG_PWD)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("WDR_PWD_LOCKED"))
                .andReturn();
        String wdrNo = body(locked).path("detail").path("wdrNo").asText();

        assertThat(acntStatCd(t.acntNo())).isEqualTo("TEMP_LOCKED");
        assertThat(dpsBal(t.acntNo())).isEqualTo(PKG_10M);
        assertThat(db.queryForObject("SELECT wdr_stat_cd FROM withdrawal WHERE wdr_no = ?", String.class, wdrNo))
                .isEqualTo("REJECTED");

        Map<String, Object> action = db.queryForMap(
                "SELECT act_dv_cd, tgt_dtc_seq, xmin::text AS tx FROM fds_action_log WHERE tgt_acnt_no = ?", t.acntNo());
        assertThat(action.get("act_dv_cd")).isEqualTo("TEMP_LOCK");
        Map<String, Object> dtc = db.queryForMap(
                "SELECT rslt_cd, wdr_no, xmin::text AS tx FROM fds_detection WHERE dtc_seq = ?", action.get("tgt_dtc_seq"));
        assertThat(dtc.get("rslt_cd")).isEqualTo("LOCKED");
        assertThat(dtc.get("wdr_no")).isEqualTo(wdrNo);

        // 한 커밋: 네 행을 마지막으로 쓴 트랜잭션 번호(xmin)가 같음
        String accountTx = db.queryForObject("SELECT xmin::text FROM account WHERE acnt_no = ?", String.class, t.acntNo());
        String withdrawalTx = db.queryForObject("SELECT xmin::text FROM withdrawal WHERE wdr_no = ?", String.class, wdrNo);
        assertThat(List.of(withdrawalTx, (String) action.get("tx"), (String) dtc.get("tx"))).containsOnly(accountTx);
    }

    @Test
    @DisplayName("같은 멱등키 동시 주문: 10건을 동시에 보내면 1건만 체결되고 응답이 같으며, TRADE_NEW_IP 탐지는 1건만 남는다")
    void sameKeyTenOrdersAtOnce() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        putPriceNow(SAMSUNG, 70_000);
        Map<String, Object> request = orderBody(SAMSUNG, "BUY", 10, newKey());

        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            calls.add(() -> mockMvc.perform(orderRequest(t, request).with(fromIp(UNTRUSTED_IP))).andReturn());
        }
        List<MvcResult> results = concurrently(calls);

        assertThat(count(results, 201)).isEqualTo(10);
        assertThat(results.stream().map(LedgerTestSupport::content).distinct()).hasSize(1);
        assertThat(orderCount(t.acntNo())).isEqualTo(1);
        assertThat(tradeCount(t.acntNo())).isEqualTo(1);
        assertThat(dpsBal(t.acntNo())).isEqualTo(PKG_10M - 700_000L);

        // 새 IP 직후 거래 기록은 커밋 뒤 별도 스레드에서 쓰므로 기다림. 생긴 뒤 1초 더 지나도 1건이어야 중복 없음
        String sql = "SELECT COUNT(*) FROM fds_detection WHERE acnt_no = ? AND rule_cd = 'TRADE_NEW_IP'";
        await().atMost(Duration.ofSeconds(10))
                .until(() -> db.queryForObject(sql, Integer.class, t.acntNo()) >= 1);
        await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(db.queryForObject(sql, Integer.class, t.acntNo())).isEqualTo(1));
    }
}
