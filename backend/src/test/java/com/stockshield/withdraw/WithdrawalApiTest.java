package com.stockshield.withdraw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.stockshield.support.LedgerTestSupport;

/**
 * 모의 출금 API와 대량 출금 추가 인증 (TRD_WDR_001 #1~#4, FDS_WDR_001 #1~#3, TRD_ACC_001 #4,
 * API 명세서 'API_명세서' 21·22행, 연결 합의서 6절).
 *
 * 가짜가 아니라 실제 FDS(WithdrawLimitChecker·DetectionRecorder)로 돈다. 원장이 쓰는 출금·계좌·조치 이력과
 * FDS가 쓰는 탐지 기록이 한 커밋으로 맞물리는지를 함께 본다. 테스트 회원의 출금 비밀번호는 TEST_WDR_PWD.
 */
class WithdrawalApiTest extends LedgerTestSupport {

    private static final String WRONG_PWD = "000000";

    @Test
    @DisplayName("소액 출금은 비밀번호 없이 완료되고 예수금 차감·출금 내역(출금 후 잔액)이 함께 남는다. 받는 은행·계좌는 정규화해 저장한다")
    void smallWithdrawalIsDone() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        Map<String, Object> request = withdrawBody(1_000_000, newKey(), null);
        request.put("rcvBankNm", "  국민은행 ");

        MvcResult r = mockMvc.perform(withdrawRequest(t, request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.wdrAmt").value(1_000_000))
                .andExpect(jsonPath("$.dpsBal").value(9_000_000))
                .andExpect(jsonPath("$.addAuthYn").value("N"))
                .andExpect(jsonPath("$.wdrStatCd").value("DONE"))
                .andExpect(jsonPath("$.regTs").value("2026-11-16T10:00:00+09:00"))
                .andReturn();
        String wdrNo = body(r).path("wdrNo").asText();
        assertThat(wdrNo).matches("W20261116\\d{9}");

        Map<String, Object> row = db.queryForMap("SELECT * FROM withdrawal WHERE wdr_no = ?", wdrNo);
        assertThat(row.get("wdr_stat_cd")).isEqualTo("DONE");
        assertThat(row.get("rcv_bank_nm")).isEqualTo("국민은행");
        assertThat(row.get("rcv_acnt_no")).isEqualTo("123456789012");
        assertThat(row.get("add_auth_yn")).isEqualTo("N");
        assertThat(row.get("post_pwd_fail_cnt")).isNull();
        assertThat(((Number) row.get("post_dps_bal")).longValue()).isEqualTo(9_000_000L);
        assertThat(row.get("trc_id")).isEqualTo(r.getResponse().getHeader("X-Trace-Id"));
        assertThat(row.get("dvc_id")).isEqualTo(t.member().deviceId());

        assertThat(dpsBal(t.acntNo())).isEqualTo(9_000_000L).isEqualTo(expectedDpsBal(t.acntNo()));
        // 대량 출금이 아니면 탐지 기록이 없다
        assertThat(detectionCount(t.acntNo())).isZero();
    }

    @Test
    @DisplayName("500만 원은 비밀번호가 없으면 428(저장 안 함), 같은 키에 내용을 바꾸면 409, 같은 키에 맞는 비밀번호를 붙이면 출금되고 탐지 기록이 PASS로 이어진다")
    void largeWithdrawalNeedsPasswordThenContinuesWithSameKey() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        String key = newKey();

        mockMvc.perform(withdrawRequest(t, withdrawBody(5_000_000, key, null)))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("WDR_PWD_REQUIRED"))
                .andExpect(jsonPath("$.detail.reason").value("SINGLE"))
                .andExpect(jsonPath("$.detail.dailySum").value(0))
                .andExpect(jsonPath("$.detail.threshold").value(5_000_000))
                .andExpect(header().doesNotExist("Idempotency-Replayed"));
        assertThat(withdrawalCount(t.acntNo())).isZero();
        assertThat(dpsBal(t.acntNo())).isEqualTo(PKG_10M);
        assertThat(detection(t.acntNo(), key).get("rslt_cd")).isEqualTo("PENDING");

        // 비밀번호 없이 다시 보내면 다시 428이고 대기 기록은 하나 그대로
        mockMvc.perform(withdrawRequest(t, withdrawBody(5_000_000, key, null)))
                .andExpect(status().isPreconditionRequired());
        assertThat(detectionCount(t.acntNo())).isEqualTo(1);

        mockMvc.perform(withdrawRequest(t, withdrawBody(5_000_001, key, TEST_WDR_PWD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(withdrawalCount(t.acntNo())).isZero();

        // 공백·하이픈만 다른 같은 요청은 같은 내용이다
        Map<String, Object> withPwd = withdrawBody(5_000_000, key, TEST_WDR_PWD);
        withPwd.put("rcvBankNm", " 국민은행");
        withPwd.put("rcvAcntNo", "123456789012");
        MvcResult done = mockMvc.perform(withdrawRequest(t, withPwd))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.addAuthYn").value("Y"))
                .andExpect(jsonPath("$.dpsBal").value(5_000_000))
                .andReturn();
        String wdrNo = body(done).path("wdrNo").asText();
        assertThat(db.queryForObject("SELECT post_pwd_fail_cnt FROM withdrawal WHERE wdr_no = ?", Integer.class, wdrNo))
                .isZero();
        Map<String, Object> dtc = detection(t.acntNo(), key);
        assertThat(dtc.get("rslt_cd")).isEqualTo("PASS");
        assertThat(dtc.get("wdr_no")).isEqualTo(wdrNo);
        assertThat(detectionCount(t.acntNo())).isEqualTo(1);

        // 결과가 저장된 뒤에는 같은 키 재요청이 처음 응답을 그대로 받는다
        MvcResult again = mockMvc.perform(withdrawRequest(t, withdrawBody(5_000_000, key, null)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andReturn();
        assertThat(content(again)).isEqualTo(content(done));
        assertThat(dpsBal(t.acntNo())).isEqualTo(5_000_000L);
    }

    @Test
    @DisplayName("24시간 누적(이번 포함) 500만 원 경계: 합계 4,999,999원까지는 통과, 1원을 더하면 428(DAILY_SUM). 정확히 24시간 전 출금은 합계에 들어가고 1초 지나면 빠진다")
    void dailySumBoundary() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        mockMvc.perform(withdrawRequest(t, withdrawBody(3_000_000, newKey(), null))).andExpect(status().isCreated());
        mockMvc.perform(withdrawRequest(t, withdrawBody(1_999_999, newKey(), null))).andExpect(status().isCreated());

        mockMvc.perform(withdrawRequest(t, withdrawBody(1, newKey(), null)))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.detail.reason").value("DAILY_SUM"))
                .andExpect(jsonPath("$.detail.dailySum").value(4_999_999));

        t = moveTo(t, MARKET_OPEN.plusDays(1));
        mockMvc.perform(withdrawRequest(t, withdrawBody(1, newKey(), null)))
                .andExpect(status().isPreconditionRequired());

        t = moveTo(t, MARKET_OPEN.plusDays(1).plusSeconds(1));
        mockMvc.perform(withdrawRequest(t, withdrawBody(1, newKey(), null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.addAuthYn").value("N"));
    }

    @Test
    @DisplayName("처음부터 맞는 비밀번호를 붙인 대량 출금은 428 없이 바로 출금되고 탐지 기록이 PASS로 저장된다")
    void largeWithdrawalWithPasswordFromStart() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        String key = newKey();

        MvcResult r = mockMvc.perform(withdrawRequest(t, withdrawBody(7_000_000, key, TEST_WDR_PWD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.addAuthYn").value("Y"))
                .andExpect(jsonPath("$.dpsBal").value(3_000_000))
                .andReturn();

        Map<String, Object> dtc = detection(t.acntNo(), key);
        assertThat(dtc.get("rslt_cd")).isEqualTo("PASS");
        assertThat(dtc.get("wdr_no")).isEqualTo(body(r).path("wdrNo").asText());
    }

    @Test
    @DisplayName("출금 비밀번호를 5회 틀리면 422 네 번 뒤 423이고, 계좌 잠금·거절 출금·TEMP_LOCK 이력·LOCKED 탐지 기록이 함께 남는다. 이후 주문·출금은 ACCOUNT_LOCKED")
    void fiveWrongPasswordsLockTheAccount() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        List<Map<String, Object>> requests = new ArrayList<>();
        List<MvcResult> rejected = new ArrayList<>();

        for (int attempt = 1; attempt <= 4; attempt++) {
            Map<String, Object> request = withdrawBody(5_000_000, newKey(), WRONG_PWD);
            requests.add(request);
            rejected.add(mockMvc.perform(withdrawRequest(t, request))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("WDR_PWD_MISMATCH"))
                    .andExpect(jsonPath("$.detail.wdrStatCd").value("REJECTED"))
                    .andExpect(jsonPath("$.detail.remainCnt").value(5 - attempt))
                    .andReturn());
            assertThat(wdrPwdFailCnt(t.acntNo())).isEqualTo(attempt);
            assertThat(acntStatCd(t.acntNo())).isEqualTo("ACTIVE");
        }

        MvcResult locked = mockMvc.perform(withdrawRequest(t, withdrawBody(5_000_000, newKey(), WRONG_PWD)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("WDR_PWD_LOCKED"))
                .andExpect(jsonPath("$.detail.wdrStatCd").value("REJECTED"))
                .andReturn();
        String lockedWdrNo = body(locked).path("detail").path("wdrNo").asText();

        assertThat(acntStatCd(t.acntNo())).isEqualTo("TEMP_LOCKED");
        assertThat(wdrPwdFailCnt(t.acntNo())).isEqualTo(5);
        assertThat(dpsBal(t.acntNo())).isEqualTo(PKG_10M);
        Map<String, Object> lockedRow = db.queryForMap("SELECT * FROM withdrawal WHERE wdr_no = ?", lockedWdrNo);
        assertThat(lockedRow.get("rjct_rsn_cd")).isEqualTo("WDR_PWD_LOCKED");
        assertThat(lockedRow.get("add_auth_yn")).isEqualTo("Y");
        assertThat(lockedRow.get("post_pwd_fail_cnt")).isEqualTo(5);

        // 잠금 이력은 시스템이 남기고, 원인인 LOCKED 탐지 기록과 그 탐지 기록의 출금번호로 이어진다
        List<Map<String, Object>> actions = db.queryForList(
                "SELECT * FROM fds_action_log WHERE tgt_acnt_no = ?", t.acntNo());
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).get("act_dv_cd")).isEqualTo("TEMP_LOCK");
        assertThat(actions.get(0).get("act_actor_dv_cd")).isEqualTo("SYSTEM");
        assertThat(actions.get(0).get("proc_mbr_no")).isNull();
        Map<String, Object> dtc = db.queryForMap("SELECT rule_cd, rslt_cd, wdr_no FROM fds_detection WHERE dtc_seq = ?",
                actions.get(0).get("tgt_dtc_seq"));
        assertThat(dtc.get("rule_cd")).isEqualTo("WITHDRAW_LARGE");
        assertThat(dtc.get("rslt_cd")).isEqualTo("LOCKED");
        assertThat(dtc.get("wdr_no")).isEqualTo(lockedWdrNo);

        // 잠긴 뒤에는 맞는 비밀번호도, 소액 출금도, 주문도 거절된다(거절 저장)
        mockMvc.perform(withdrawRequest(t, withdrawBody(5_000_000, newKey(), TEST_WDR_PWD)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
        mockMvc.perform(withdrawRequest(t, withdrawBody(10_000, newKey(), null)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
        putPriceNow(SAMSUNG, 70_000);
        mockMvc.perform(orderRequest(t, orderBody(SAMSUNG, "BUY", 1, newKey())))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
        assertThat(wdrPwdFailCnt(t.acntNo())).isEqualTo(5);

        // 거절도 결과다: 두 번째 시도의 키를 다시 보내면 남은 횟수(3)까지 처음 응답 그대로
        MvcResult replay = mockMvc.perform(withdrawRequest(t, requests.get(1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.detail.remainCnt").value(3))
                .andReturn();
        assertThat(content(replay)).isEqualTo(content(rejected.get(1)));

        assertThat(withdrawalCount(t.acntNo())).isEqualTo(7);
    }

    @Test
    @DisplayName("비밀번호가 맞으면 오류 횟수가 0으로 돌아간다. 이후 예수금 부족으로 거절돼도 0으로 남고 탐지 기록은 PASS다")
    void matchingPasswordResetsFailCount() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(withdrawRequest(t, withdrawBody(5_000_000, newKey(), WRONG_PWD)))
                    .andExpect(status().isUnprocessableEntity());
        }
        assertThat(wdrPwdFailCnt(t.acntNo())).isEqualTo(2);

        String key = newKey();
        MvcResult r = mockMvc.perform(withdrawRequest(t, withdrawBody(20_000_000, key, TEST_WDR_PWD)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.detail.remainCnt").doesNotExist())
                .andReturn();

        assertThat(wdrPwdFailCnt(t.acntNo())).isZero();
        assertThat(dpsBal(t.acntNo())).isEqualTo(PKG_10M);
        String wdrNo = body(r).path("detail").path("wdrNo").asText();
        Map<String, Object> row = db.queryForMap("SELECT * FROM withdrawal WHERE wdr_no = ?", wdrNo);
        assertThat(row.get("add_auth_yn")).isEqualTo("Y");
        assertThat(row.get("post_pwd_fail_cnt")).isEqualTo(0);
        assertThat(row.get("post_dps_bal")).isNull();
        assertThat(detection(t.acntNo(), key).get("rslt_cd")).isEqualTo("PASS");
        assertThat(detection(t.acntNo(), key).get("wdr_no")).isEqualTo(wdrNo);
    }

    @Test
    @DisplayName("대량 출금이 아니면 보낸 비밀번호를 확인하지 않는다(틀려도 출금, 오류 횟수 그대로). 예수금이 모자라면 거절을 저장하고 정확히 맞으면 출금된다")
    void smallWithdrawalIgnoresPasswordAndChecksBalance() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        mockMvc.perform(withdrawRequest(t, withdrawBody(100_000, newKey(), WRONG_PWD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.addAuthYn").value("N"));
        assertThat(wdrPwdFailCnt(t.acntNo())).isZero();

        db.update("UPDATE account SET dps_bal = 50000 WHERE acnt_no = ?", t.acntNo());
        MvcResult r = mockMvc.perform(withdrawRequest(t, withdrawBody(50_001, newKey(), null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.detail.wdrStatCd").value("REJECTED"))
                .andReturn();
        Map<String, Object> row = db.queryForMap("SELECT * FROM withdrawal WHERE wdr_no = ?",
                body(r).path("detail").path("wdrNo").asText());
        assertThat(row.get("add_auth_yn")).isEqualTo("N");
        assertThat(row.get("post_dps_bal")).isNull();
        assertThat(dpsBal(t.acntNo())).isEqualTo(50_000L);

        mockMvc.perform(withdrawRequest(t, withdrawBody(50_000, newKey(), null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dpsBal").value(0));
        assertThat(dpsBal(t.acntNo())).isZero();
    }

    @Test
    @DisplayName("428을 받은 뒤 계좌가 잠기면 같은 키의 재요청은 ACCOUNT_LOCKED로 저장되고, 대기 탐지 기록은 건드리지 않는다")
    void accountLockedAfterPasswordRequired() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        String key = newKey();
        mockMvc.perform(withdrawRequest(t, withdrawBody(5_000_000, key, null)))
                .andExpect(status().isPreconditionRequired());

        db.update("UPDATE account SET acnt_stat_cd = 'LOCKED' WHERE acnt_no = ?", t.acntNo());
        mockMvc.perform(withdrawRequest(t, withdrawBody(5_000_000, key, TEST_WDR_PWD)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

        assertThat(withdrawalCount(t.acntNo())).isEqualTo(1);
        Map<String, Object> dtc = detection(t.acntNo(), key);
        assertThat(dtc.get("rslt_cd")).isEqualTo("PENDING");
        assertThat(dtc.get("wdr_no")).isNull();
    }

    @Test
    @DisplayName("입력값이 틀리면 400이고 아무것도 저장하지 않는다. 출금 비밀번호 형식 오류는 오류 횟수에 넣지 않는다")
    void invalidRequestsStoreNothing() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        List<Map<String, Object>> bad = new ArrayList<>();
        bad.add(withdrawBody(0, newKey(), null));
        bad.add(withdrawBody(-1, newKey(), null));
        bad.add(withdrawBody(10_000, "not-a-uuid", null));
        bad.add(withdrawBody(10_000, null, null));
        bad.add(with(withdrawBody(10_000, newKey(), null), "rcvAcntNo", "12345"));
        bad.add(with(withdrawBody(10_000, newKey(), null), "rcvAcntNo", "1234-abcd-5678"));
        bad.add(with(withdrawBody(10_000, newKey(), null), "rcvAcntNo", "12345678901234567"));
        bad.add(with(withdrawBody(10_000, newKey(), null), "rcvAcntNo", null));
        bad.add(with(withdrawBody(10_000, newKey(), null), "rcvBankNm", "   "));
        bad.add(with(withdrawBody(10_000, newKey(), null), "rcvBankNm", "가".repeat(51)));
        bad.add(withdrawBody(5_000_000, newKey(), "12345"));
        bad.add(withdrawBody(5_000_000, newKey(), "abcdef"));
        bad.add(withdrawBody(5_000_000, newKey(), ""));

        for (Map<String, Object> request : bad) {
            mockMvc.perform(withdrawRequest(t, request))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }

        assertThat(withdrawalCount(t.acntNo())).isZero();
        assertThat(detectionCount(t.acntNo())).isZero();
        assertThat(wdrPwdFailCnt(t.acntNo())).isZero();
        assertThat(dpsBal(t.acntNo())).isEqualTo(PKG_10M);

        // 받는 은행 50자, 받는 계좌 숫자 10자리·16자리는 받는다
        mockMvc.perform(withdrawRequest(t, with(withdrawBody(1, newKey(), null), "rcvBankNm", "가".repeat(50))))
                .andExpect(status().isCreated());
        mockMvc.perform(withdrawRequest(t, with(withdrawBody(1, newKey(), null), "rcvAcntNo", "1234567890")))
                .andExpect(status().isCreated());
        mockMvc.perform(withdrawRequest(t, with(withdrawBody(1, newKey(), null), "rcvAcntNo", "1234-5678-9012-3456")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("출금 내역은 완료·거절을 최신순으로 보여 주고 받는 계좌는 뒷 4자리만 보인다. 다른 회원의 내역은 보이지 않는다")
    void historyIsLatestFirstAndMasked() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        String first = wdrNoOf(mockMvc.perform(withdrawRequest(t, withdrawBody(100_000, newKey(), null))).andReturn());
        t = moveTo(t, MARKET_OPEN.plusSeconds(1));
        String second = body(mockMvc.perform(withdrawRequest(t, withdrawBody(5_000_000, newKey(), WRONG_PWD)))
                .andReturn()).path("detail").path("wdrNo").asText();
        t = moveTo(t, MARKET_OPEN.plusSeconds(2));
        String third = wdrNoOf(mockMvc.perform(withdrawRequest(t, withdrawBody(200_000, newKey(), null))).andReturn());

        mockMvc.perform(historyRequest(t).param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].wdrNo").value(third))
                .andExpect(jsonPath("$.content[0].regTs").value("2026-11-16T10:00:02+09:00"))
                .andExpect(jsonPath("$.content[0].wdrAmt").value(200_000))
                .andExpect(jsonPath("$.content[0].rcvBankNm").value("국민은행"))
                .andExpect(jsonPath("$.content[0].mskRcvAcntNo").value("********9012"))
                .andExpect(jsonPath("$.content[0].wdrStatCd").value("DONE"))
                .andExpect(jsonPath("$.content[0].rjctRsnCd").isEmpty())
                .andExpect(jsonPath("$.content[0].postDpsBal").value(9_700_000))
                .andExpect(jsonPath("$.content[1].wdrNo").value(second))
                .andExpect(jsonPath("$.content[1].addAuthYn").value("Y"))
                .andExpect(jsonPath("$.content[1].wdrStatCd").value("REJECTED"))
                .andExpect(jsonPath("$.content[1].rjctRsnCd").value("WDR_PWD_MISMATCH"))
                .andExpect(jsonPath("$.content[1].rjctRsnMsg").value("출금 비밀번호가 틀렸습니다."))
                .andExpect(jsonPath("$.content[1].postDpsBal").isEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(historyRequest(t).param("page", "1").param("size", "2"))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].wdrNo").value(first));
        // 받는 계좌번호 전체는 응답에 없다
        assertThat(content(mockMvc.perform(historyRequest(t)).andExpect(status().isOk()).andReturn()))
                .doesNotContain("123456789012");

        mockMvc.perform(historyRequest(t).param("size", "101")).andExpect(status().isBadRequest());
        mockMvc.perform(historyRequest(t).param("size", "0")).andExpect(status().isBadRequest());
        mockMvc.perform(historyRequest(t).param("page", "-1")).andExpect(status().isBadRequest());

        Trader other = newTraderAt(MARKET_OPEN.plusSeconds(3));
        mockMvc.perform(historyRequest(other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    private MockHttpServletRequestBuilder historyRequest(Trader t) {
        return get("/api/v1/accounts/me/withdrawals").header("Authorization", bearer(t.tokens()));
    }

    private String wdrNoOf(MvcResult created) throws Exception {
        return body(created).path("wdrNo").asText();
    }

    private static Map<String, Object> with(Map<String, Object> body, String field, Object value) {
        body.put(field, value);
        return body;
    }

    /** 실제 FDS가 남긴 대량 출금 탐지 기록(계좌·멱등키로 하나) */
    private Map<String, Object> detection(String acntNo, String idmpKey) {
        return db.queryForMap(
                "SELECT rslt_cd, wdr_no FROM fds_detection WHERE acnt_no = ? AND req_idmp_key = ? AND rule_cd = 'WITHDRAW_LARGE'",
                acntNo, idmpKey);
    }
}
