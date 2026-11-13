package com.stockshield.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.stockshield.support.LedgerTestSupport;

/**
 * 관리자 계좌 잠금·해제 (FDS_ALT_001 #4, FDS_WDR_001 #3, TRD_LED_001 #2·#4, API 명세서 'API_명세서' 27행).
 * 진행 중인 거래와의 순서는 테스트가 계좌 락을 잡은 트랜잭션을 열어 두고 확인함. 테스트 메서드에는 @Transactional을 붙이지 않음.
 */
class AdminAccountStatusApiTest extends LedgerTestSupport {

    @Autowired PlatformTransactionManager txManager;

    private MockHttpServletRequestBuilder statusRequest(Tokens admin, String acntNo, String acntStatCd,
                                                        String actRsnCont) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("acntStatCd", acntStatCd);
        body.put("actRsnCont", actRsnCont);
        return put("/api/v1/admin/accounts/" + acntNo + "/status").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content(json(body));
    }

    private int actionCount(String acntNo, String actDvCd) {
        return db.queryForObject("SELECT COUNT(*) FROM fds_action_log WHERE tgt_acnt_no = ? AND act_dv_cd = ?",
                Integer.class, acntNo, actDvCd);
    }

    @Test
    @DisplayName("관리자가 잠그면 LOCKED가 되고 이후 주문·출금은 423 ACCOUNT_LOCKED다. 같은 상태로 다시 바꾸면 409 SAME_STATUS")
    void lockRejectsTrading() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        putPriceNow(SAMSUNG, 70_000);
        TestMember adminMember = createAdmin();
        Tokens admin = loginAsAdmin(adminMember);

        mockMvc.perform(statusRequest(admin, t.acntNo(), "LOCKED", "이상 거래 의심"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acntNo").value(t.acntNo()))
                .andExpect(jsonPath("$.acntStatCd").value("LOCKED"));
        assertThat(acntStatCd(t.acntNo())).isEqualTo("LOCKED");

        mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "BUY", 1, newKey())))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
        mockMvc.perform(withdrawRequest(t, withdrawBody(10_000, newKey(), null)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
        assertThat(dpsBal(t.acntNo())).isEqualTo(PKG_10M);

        mockMvc.perform(statusRequest(admin, t.acntNo(), "LOCKED", "한 번 더"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SAME_STATUS"));

        Map<String, Object> log = db.queryForMap(
                "SELECT * FROM fds_action_log WHERE tgt_acnt_no = ? AND act_dv_cd = 'LOCK'", t.acntNo());
        assertThat(log).containsEntry("act_actor_dv_cd", "ADMIN").containsEntry("act_rsn_cont", "이상 거래 의심");
        assertThat(((Number) log.get("proc_mbr_no")).longValue()).isEqualTo(adminMember.mbrNo());
        assertThat(log.get("tgt_dtc_seq")).isNull();
    }

    @Test
    @DisplayName("출금 비밀번호 5회 오류로 잠긴(TEMP_LOCKED) 계좌를 풀면 ACTIVE·오류 횟수 0이 되고 UNLOCK 이력이 남으며 다시 출금할 수 있다")
    void unlockResetsFailCount() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        db.update("UPDATE account SET acnt_stat_cd = 'TEMP_LOCKED', wdr_pwd_fail_cnt = 5 WHERE acnt_no = ?", t.acntNo());
        Tokens admin = loginAsAdmin(createAdmin());

        mockMvc.perform(statusRequest(admin, t.acntNo(), "ACTIVE", "본인 확인 후 해제"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acntStatCd").value("ACTIVE"));

        assertThat(acntStatCd(t.acntNo())).isEqualTo("ACTIVE");
        assertThat(wdrPwdFailCnt(t.acntNo())).isZero();
        assertThat(actionCount(t.acntNo(), "UNLOCK")).isEqualTo(1);
        mockMvc.perform(withdrawRequest(t, withdrawBody(10_000, newKey(), null))).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("바꿀 상태가 ACTIVE·LOCKED가 아니거나 사유가 없으면 400, 없는 계좌는 404. 어느 경우에도 계좌와 이력은 그대로다")
    void rejectsInvalidRequests() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        Tokens admin = loginAsAdmin(createAdmin());

        mockMvc.perform(statusRequest(admin, t.acntNo(), "TEMP_LOCKED", "사유"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(statusRequest(admin, t.acntNo(), "LOCKED", " "))
                .andExpect(status().isBadRequest());
        mockMvc.perform(statusRequest(admin, t.acntNo(), "LOCKED", null))
                .andExpect(status().isBadRequest());
        mockMvc.perform(statusRequest(admin, "999999999999", "LOCKED", "사유"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertThat(acntStatCd(t.acntNo())).isEqualTo("ACTIVE");
        assertThat(actionCount(t.acntNo(), "LOCK")).isZero();
    }

    @Test
    @DisplayName("진행 중인 거래가 계좌 락을 잡고 있으면 해제 요청은 기다렸다가, 그 거래가 커밋한 상태(TEMP_LOCKED)를 보고 해제한다")
    void unlockWaitsForInFlightLedgerTransaction() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        Tokens admin = loginAsAdmin(createAdmin());
        ExecutorService other = Executors.newSingleThreadExecutor();
        AtomicReference<Future<MvcResult>> unlock = new AtomicReference<>();
        try {
            // 5회째 출금 비밀번호 오류를 처리 중인 출금 트랜잭션처럼: 계좌 락 → TEMP_LOCKED, 아직 커밋 전
            new TransactionTemplate(txManager).executeWithoutResult(tx -> {
                db.queryForObject("SELECT acnt_no FROM account WHERE acnt_no = ? FOR NO KEY UPDATE", String.class,
                        t.acntNo());
                db.update("UPDATE account SET acnt_stat_cd = 'TEMP_LOCKED', wdr_pwd_fail_cnt = 5 WHERE acnt_no = ?",
                        t.acntNo());
                unlock.set(other.submit(() ->
                        mockMvc.perform(statusRequest(admin, t.acntNo(), "ACTIVE", "본인 확인 후 해제")).andReturn()));
                pause(500);
                assertThat(unlock.get().isDone()).as("계좌 락을 기다려야 함").isFalse();
            });
            MvcResult r = unlock.get().get(5, TimeUnit.SECONDS);

            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(acntStatCd(t.acntNo())).isEqualTo("ACTIVE");
            assertThat(wdrPwdFailCnt(t.acntNo())).isZero();
            assertThat(actionCount(t.acntNo(), "UNLOCK")).isEqualTo(1);
        } finally {
            other.shutdownNow();
        }
    }

    @Test
    @DisplayName("계좌 락을 2초 넘게 기다리면 503 LOCK_TIMEOUT(Retry-After: 1)이고 상태·이력은 바뀌지 않는다")
    void lockTimeoutReturns503() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        Tokens admin = loginAsAdmin(createAdmin());
        ExecutorService other = Executors.newSingleThreadExecutor();
        AtomicReference<MvcResult> result = new AtomicReference<>();
        try {
            new TransactionTemplate(txManager).executeWithoutResult(tx -> {
                db.queryForObject("SELECT acnt_no FROM account WHERE acnt_no = ? FOR NO KEY UPDATE", String.class,
                        t.acntNo());
                Future<MvcResult> lock = other.submit(() ->
                        mockMvc.perform(statusRequest(admin, t.acntNo(), "LOCKED", "잠금")).andReturn());
                try {
                    result.set(lock.get(10, TimeUnit.SECONDS));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        } finally {
            other.shutdownNow();
        }

        mockMvc.perform(statusRequest(admin, t.acntNo(), "ACTIVE", "확인"))
                .andExpect(status().isConflict());
        MvcResult r = result.get();
        assertThat(r.getResponse().getStatus()).isEqualTo(503);
        assertThat(r.getResponse().getHeader("Retry-After")).isEqualTo("1");
        assertThat(body(r).path("code").asText()).isEqualTo("LOCK_TIMEOUT");
        assertThat(acntStatCd(t.acntNo())).isEqualTo("ACTIVE");
        assertThat(actionCount(t.acntNo(), "LOCK")).isZero();
    }

    @Test
    @DisplayName("일반 사용자는 계좌 상태를 바꿀 수 없다(403)")
    void userCannotChangeStatus() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        mockMvc.perform(statusRequest(t.tokens(), t.acntNo(), "LOCKED", "사유"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Retry-After"));
        assertThat(acntStatCd(t.acntNo())).isEqualTo("ACTIVE");
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
