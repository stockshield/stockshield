package com.stockshield.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 원장 대사 수동 실행·이력·상세 API와 관제 경보 (ADM_REC_001 #1~#4, FDS_ALT_001 #7, API 명세서 'API_명세서' 28~30행).
 * 실제 PostgreSQL에서 원장을 직접 틀어 불일치를 만들고 확인함.
 */
class ReconcileApiTest extends ReconcileTestSupport {

    @Autowired
    @Qualifier("brokerChannel")
    AbstractSubscribableChannel brokerChannel;

    private ChannelInterceptor installed;

    @AfterEach
    void removeBrokerInterceptor() {
        if (installed != null) {
            brokerChannel.removeInterceptor(installed);
            installed = null;
        }
    }

    /** 원장과 내역이 맞는 계좌: 매수 1건(삼성전자 10주) + 출금 1건 */
    private Trader cleanTrader() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        putPriceNow(SAMSUNG, 70_000);
        assertThat(order(t, SAMSUNG, "BUY", 10).getResponse().getStatus()).isEqualTo(201);
        mockMvc.perform(withdrawRequest(t, withdrawBody(1_000_000, newKey(), null))).andExpect(status().isCreated());
        return t;
    }

    @Test
    @DisplayName("원장과 내역이 맞으면 예수금·종목별 수량 모두 일치(N)로 저장된다. 일치한 건도 남는다")
    void matchingLedgerIsStoredAsMatch() throws Exception {
        Trader t = cleanTrader();
        Tokens admin = loginAsAdmin(createAdmin());

        MvcResult r = runManually(admin);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = body(r);
        long runNo = body.path("rcclRunNo").asLong();
        assertThat(body.path("runStatCd").asText()).isEqualTo("DONE");
        assertThat(body.path("rcclDt").asText()).isEqualTo("20261116");
        assertThat(body.path("totAcntCnt").asInt()).isPositive();
        assertThat(body.path("failRsnCont").isNull()).isTrue();

        List<Map<String, Object>> mine = resultsOf(runNo, t.acntNo());
        assertThat(mine).hasSize(2);
        assertThat(mine.get(0)).containsEntry("chk_item_cd", "DEPOSIT").containsEntry("diff_yn", "N");
        assertThat(num(mine.get(0).get("exp_val"))).isEqualTo(num(mine.get(0).get("actl_val")))
                .isEqualTo(PKG_10M - 700_000 - 1_000_000);
        assertThat(mine.get(1)).containsEntry("chk_item_cd", "HOLDING").containsEntry("tck_cd", SAMSUNG)
                .containsEntry("diff_yn", "N");
        assertThat(num(mine.get(1).get("exp_val"))).isEqualTo(10);

        Map<String, Object> run = runRow(runNo);
        assertThat(run).containsEntry("trg_dv_cd", "MANUAL").containsEntry("run_stat_cd", "DONE");
        assertThat(run.get("end_ts")).isNotNull();
    }

    @Test
    @DisplayName("예수금·보유 수량이 틀어지거나 잔고에만 있는 종목이 있으면 불일치(Y)이고 기대값·실제값이 남는다")
    void tamperedLedgerIsDetected() throws Exception {
        Trader t = cleanTrader();
        db.update("UPDATE account SET dps_bal = dps_bal + 1000 WHERE acnt_no = ?", t.acntNo());
        db.update("UPDATE holding SET hld_qty = hld_qty - 1 WHERE acnt_no = ? AND tck_cd = ?", t.acntNo(), SAMSUNG);
        db.update("INSERT INTO holding (acnt_no, tck_cd, hld_qty, avg_buy_prc) VALUES (?, ?, 0, 0)", t.acntNo(), HYNIX);
        Tokens admin = loginAsAdmin(createAdmin());

        JsonNode body = body(runManually(admin));
        long runNo = body.path("rcclRunNo").asLong();
        assertThat(body.path("diffAcntCnt").asInt()).isPositive();

        List<Map<String, Object>> mine = resultsOf(runNo, t.acntNo());
        assertThat(mine).hasSize(3).allSatisfy(row -> assertThat(row).containsEntry("diff_yn", "Y"));
        long expDeposit = PKG_10M - 700_000 - 1_000_000;
        assertThat(num(mine.get(0).get("exp_val"))).isEqualTo(expDeposit);
        assertThat(num(mine.get(0).get("actl_val"))).isEqualTo(expDeposit + 1000);
        assertThat(mine.get(1)).containsEntry("tck_cd", HYNIX);
        // 수량은 0 = 0이지만 내역 없이 잔고에만 있음
        assertThat(num(mine.get(1).get("exp_val"))).isZero();
        assertThat(num(mine.get(1).get("actl_val"))).isZero();
        assertThat(mine.get(2)).containsEntry("tck_cd", SAMSUNG);
        assertThat(num(mine.get(2).get("exp_val"))).isEqualTo(10);
        assertThat(num(mine.get(2).get("actl_val"))).isEqualTo(9);
    }

    @Test
    @DisplayName("상세 조회는 기본(diffOnly=true)이면 불일치 항목만, false면 일치 항목까지 돌려준다. 없는 실행은 404")
    void detailFiltersByDiffOnly() throws Exception {
        Trader broken = cleanTrader();
        db.update("UPDATE account SET dps_bal = dps_bal + 1 WHERE acnt_no = ?", broken.acntNo());
        Trader clean = cleanTrader();
        Tokens admin = loginAsAdmin(createAdmin());
        long runNo = body(runManually(admin)).path("rcclRunNo").asLong();

        JsonNode diffOnly = body(mockMvc.perform(get("/api/v1/admin/reconcile/" + runNo)
                .header("Authorization", bearer(admin))).andExpect(status().isOk()).andReturn());
        assertThat(diffOnly.path("runStatCd").asText()).isEqualTo("DONE");
        assertThat(accountsIn(diffOnly)).contains(broken.acntNo()).doesNotContain(clean.acntNo());
        diffOnly.path("results").forEach(item -> assertThat(item.path("diffYn").asText()).isEqualTo("Y"));

        JsonNode all = body(mockMvc.perform(get("/api/v1/admin/reconcile/" + runNo).param("diffOnly", "false")
                .header("Authorization", bearer(admin))).andExpect(status().isOk()).andReturn());
        assertThat(accountsIn(all)).contains(broken.acntNo(), clean.acntNo());

        mockMvc.perform(get("/api/v1/admin/reconcile/" + Long.MAX_VALUE).header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    private static List<String> accountsIn(JsonNode detail) {
        List<String> acntNos = new ArrayList<>();
        detail.path("results").forEach(item -> acntNos.add(item.path("acntNo").asText()));
        return acntNos;
    }

    @Test
    @DisplayName("이미 실행 중이면 409 RECONCILE_RUNNING이고 새 실행 행을 만들지 않는다")
    void runningReturns409() throws Exception {
        testClock.set(MARKET_OPEN);
        Tokens admin = loginAsAdmin(createAdmin());
        insertRunning(MARKET_OPEN.minusMinutes(5));
        long before = db.queryForObject("SELECT COUNT(*) FROM reconcile_run", Long.class);

        MvcResult r = runManually(admin);

        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(r).path("code").asText()).isEqualTo("RECONCILE_RUNNING");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM reconcile_run", Long.class)).isEqualTo(before);
    }

    @Test
    @DisplayName("시작 후 30분이 지난 RUNNING은 새 실행 전에 FAILED로 정리된다. 29분 59초면 아직 실행 중으로 보고 409")
    void staleRunningIsCleanedAtThirtyMinutes() throws Exception {
        testClock.set(MARKET_OPEN);
        Tokens admin = loginAsAdmin(createAdmin());
        long stale = insertRunning(MARKET_OPEN.minusMinutes(30).plusSeconds(1));

        assertThat(runManually(admin).getResponse().getStatus()).isEqualTo(409);
        assertThat(runRow(stale)).containsEntry("run_stat_cd", "RUNNING");

        testClock.advance(Duration.ofSeconds(1));
        MvcResult r = runManually(admin);

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r).path("runStatCd").asText()).isEqualTo("DONE");
        assertThat(runRow(stale)).containsEntry("run_stat_cd", "FAILED")
                .containsEntry("fail_rsn_cont", ReconcileService.STALE_REASON);
        assertThat(runRow(stale).get("end_ts")).isNotNull();
    }

    @Test
    @DisplayName("실행 이력은 최신순 공통 페이징 형식이고 실행 구분·시작·종료 시각이 있다. size가 범위 밖이면 400")
    void historyIsPagedLatestFirst() throws Exception {
        // 다른 테스트의 실행보다 늦은 시각이라 첫 줄에 옴
        testClock.set(LocalDateTime.of(2031, 1, 6, 16, 5));
        Tokens admin = loginAsAdmin(createAdmin());
        long runNo = body(runManually(admin)).path("rcclRunNo").asLong();

        mockMvc.perform(get("/api/v1/admin/reconcile").param("size", "5").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].rcclRunNo").value(runNo))
                .andExpect(jsonPath("$.content[0].rcclDt").value("20310106"))
                .andExpect(jsonPath("$.content[0].trgDvCd").value("MANUAL"))
                .andExpect(jsonPath("$.content[0].runStatCd").value("DONE"))
                .andExpect(jsonPath("$.content[0].regTs").value("2031-01-06T16:05:00+09:00"))
                .andExpect(jsonPath("$.content[0].endTs").value("2031-01-06T16:05:00+09:00"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(5));

        mockMvc.perform(get("/api/v1/admin/reconcile").param("size", "101").header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("관리자만 실행할 수 있다: 일반 사용자 403 ACCESS_DENIED, 토큰 없음 401")
    void onlyAdminCanRun() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        mockMvc.perform(post("/api/v1/admin/reconcile").header("Authorization", bearer(t.tokens())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(post("/api/v1/admin/reconcile")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("불일치가 있으면 /topic/admin/reconcile로 수동 실행 응답과 같은 내용의 경보가 나간다")
    void mismatchAlertIsSentToBroker() throws Exception {
        Trader t = cleanTrader();
        db.update("UPDATE account SET dps_bal = dps_bal + 1 WHERE acnt_no = ?", t.acntNo());
        Tokens admin = loginAsAdmin(createAdmin());
        List<Message<?>> sent = captureAlerts(false);

        JsonNode response = body(runManually(admin));

        assertThat(sent).hasSize(1);
        JsonNode alert = testJson.readTree((byte[]) sent.get(0).getPayload());
        assertThat(alert).isEqualTo(response);
        assertThat(alert.path("diffAcntCnt").asInt()).isPositive();
    }

    @Test
    @DisplayName("경보 전송이 실패해도 대사 결과는 DONE으로 남고 응답도 200이다")
    void alertFailureKeepsResult() throws Exception {
        Trader t = cleanTrader();
        db.update("UPDATE account SET dps_bal = dps_bal + 1 WHERE acnt_no = ?", t.acntNo());
        Tokens admin = loginAsAdmin(createAdmin());
        captureAlerts(true);

        MvcResult r = runManually(admin);

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        long runNo = body(r).path("rcclRunNo").asLong();
        assertThat(runRow(runNo)).containsEntry("run_stat_cd", "DONE");
        assertThat(resultsOf(runNo, t.acntNo())).isNotEmpty();
    }

    /** 브로커로 가는 대사 경보를 붙잡음. fail이면 전송을 실패시킴 */
    private List<Message<?>> captureAlerts(boolean fail) {
        List<Message<?>> sent = new CopyOnWriteArrayList<>();
        installed = new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                if (ReconcileAlertListener.DESTINATION.equals(SimpMessageHeaderAccessor.getDestination(message.getHeaders()))) {
                    if (fail) {
                        throw new MessageDeliveryException(message, "테스트: 전송 실패");
                    }
                    sent.add(message);
                }
                return message;
            }
        };
        brokerChannel.addInterceptor(installed);
        return sent;
    }
}
