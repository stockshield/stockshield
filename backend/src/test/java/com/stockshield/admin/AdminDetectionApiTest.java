package com.stockshield.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockshield.support.LedgerTestSupport;

/**
 * 관리자 탐지 목록·상세·확인 (FDS_ALT_001 #2~#4·#6, API 명세서 'API_명세서' 23~25행, 연결 합의서 8절).
 * 탐지 기록은 실제 FDS가 만든 것(대량 출금 428의 WITHDRAW_LARGE, 관리자 첫 로그인의 LOGIN_NEW_ACCESS)을 씀.
 * 다른 테스트의 탐지 기록보다 최신이 되게 시각을 2030년으로 고정함(목록 첫 페이지에서 찾으려고).
 */
class AdminDetectionApiTest extends LedgerTestSupport {

    private static final LocalDateTime DETECT_TIME = LocalDateTime.of(2030, 1, 7, 10, 0);
    private static final String DETECTIONS = "/api/v1/admin/detections";

    /** 비밀번호 없는 500만 원 이상 출금 → 428, 탐지 PENDING */
    private long pendingWithdrawDetection(Trader t) throws Exception {
        mockMvc.perform(withdrawRequest(t, withdrawBody(6_000_000, newKey(), null))).andExpect(status().is(428));
        return db.queryForObject("SELECT MAX(dtc_seq) FROM fds_detection WHERE acnt_no = ? AND rule_cd = 'WITHDRAW_LARGE'",
                Long.class, t.acntNo());
    }

    private MockHttpServletRequestBuilder checkRequest(Tokens admin, long dtcSeq, String actRsnCont) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("actRsnCont", actRsnCont);
        return patch(DETECTIONS + "/" + dtcSeq + "/check").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content(json(body));
    }

    private int checkLogCount(long dtcSeq) {
        return db.queryForObject("SELECT COUNT(*) FROM fds_action_log WHERE tgt_dtc_seq = ? AND act_dv_cd = 'CHECK'",
                Integer.class, dtcSeq);
    }

    private String chkYn(long dtcSeq) {
        return db.queryForObject("SELECT chk_yn FROM fds_detection WHERE dtc_seq = ?", String.class, dtcSeq);
    }

    @Test
    @DisplayName("목록은 규칙·확인 여부로 걸러지고, 아이디는 앞 2자만 보이며, 공통 페이징 형식이다")
    void listFiltersAndMasks() throws Exception {
        Trader t = newTraderAt(DETECT_TIME);
        long dtcSeq = pendingWithdrawDetection(t);
        Tokens admin = loginAsAdmin(createAdmin());

        JsonNode page = body(mockMvc.perform(get(DETECTIONS).param("ruleCd", "WITHDRAW_LARGE").param("chkYn", "N")
                .param("size", "100").header("Authorization", bearer(admin))).andExpect(status().isOk()).andReturn());

        assertThat(page.path("page").asInt()).isZero();
        assertThat(page.path("size").asInt()).isEqualTo(100);
        assertThat(page.path("totalElements").asLong()).isPositive();
        List<JsonNode> mine = new ArrayList<>();
        page.path("content").forEach(item -> {
            assertThat(item.path("ruleCd").asText()).isEqualTo("WITHDRAW_LARGE");
            assertThat(item.path("chkYn").asText()).isEqualTo("N");
            if (item.path("dtcSeq").asLong() == dtcSeq) {
                mine.add(item);
            }
        });
        assertThat(mine).hasSize(1);
        JsonNode item = mine.get(0);
        assertThat(item.path("mskLgnId").asText()).isEqualTo(t.member().lgnId().substring(0, 2) + "***");
        assertThat(item.path("acntNo").asText()).isEqualTo(t.acntNo());
        assertThat(item.path("rsltCd").asText()).isEqualTo("PENDING");
        assertThat(item.path("regTs").asText()).isEqualTo("2030-01-07T10:00:00+09:00");
    }

    @Test
    @DisplayName("걸러 보기 값이 코드 정의 밖이거나 size가 100을 넘으면 400 INVALID_REQUEST")
    void listRejectsUnknownFilters() throws Exception {
        testClock.set(DETECT_TIME);
        Tokens admin = loginAsAdmin(createAdmin());
        for (String[] param : List.of(new String[] {"ruleCd", "LOGIN"}, new String[] {"chkYn", "y"},
                new String[] {"size", "101"}, new String[] {"page", "-1"})) {
            mockMvc.perform(get(DETECTIONS).param(param[0], param[1]).header("Authorization", bearer(admin)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    @DisplayName("상세는 상세 내용(JSON)·AI 상태·가린 IP·조회 시점 계좌 상태를 준다. 계좌 없는 탐지는 acntStatCd가 null, 없는 번호는 404")
    void detailShowsMaskedIpAndAccountStatus() throws Exception {
        Trader t = newTraderAt(DETECT_TIME);
        long dtcSeq = pendingWithdrawDetection(t);
        TestMember adminMember = createAdmin();
        Tokens admin = loginAsAdmin(adminMember);
        db.update("UPDATE account SET acnt_stat_cd = 'LOCKED' WHERE acnt_no = ?", t.acntNo());

        mockMvc.perform(get(DETECTIONS + "/" + dtcSeq).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dtcSeq").value(dtcSeq))
                .andExpect(jsonPath("$.ruleCd").value("WITHDRAW_LARGE"))
                .andExpect(jsonPath("$.rsltCd").value("PENDING"))
                .andExpect(jsonPath("$.acntNo").value(t.acntNo()))
                .andExpect(jsonPath("$.ordNo").isEmpty())
                .andExpect(jsonPath("$.wdrNo").isEmpty())
                .andExpect(jsonPath("$.dtlData").isMap())
                .andExpect(jsonPath("$.aiStatCd").value("REQUESTED"))
                .andExpect(jsonPath("$.mskConnIp").value("127.0.0.***"))
                .andExpect(jsonPath("$.userAgt").value("-"))
                .andExpect(jsonPath("$.chkYn").value("N"))
                .andExpect(jsonPath("$.regTs").value("2030-01-07T10:00:00+09:00"))
                .andExpect(jsonPath("$.acntStatCd").value("LOCKED"));

        long loginDtcSeq = db.queryForObject("SELECT MAX(dtc_seq) FROM fds_detection WHERE mbr_no = ?", Long.class,
                adminMember.mbrNo());
        mockMvc.perform(get(DETECTIONS + "/" + loginDtcSeq).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleCd").value("LOGIN_NEW_ACCESS"))
                .andExpect(jsonPath("$.acntNo").isEmpty())
                .andExpect(jsonPath("$.acntStatCd").isEmpty());

        mockMvc.perform(get(DETECTIONS + "/999999999999").header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DETECTION_NOT_FOUND"));
    }

    @Test
    @DisplayName("확인하면 chk_yn이 Y가 되고 CHECK 이력(관리자·사유)이 하나 남는다. 다시 확인하면 409이고 이력은 늘지 않는다")
    void checkOnceThenConflict() throws Exception {
        Trader t = newTraderAt(DETECT_TIME);
        long dtcSeq = pendingWithdrawDetection(t);
        TestMember adminMember = createAdmin();
        Tokens admin = loginAsAdmin(adminMember);

        mockMvc.perform(checkRequest(admin, dtcSeq, "  본인 확인 완료  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dtcSeq").value(dtcSeq))
                .andExpect(jsonPath("$.chkYn").value("Y"));

        assertThat(chkYn(dtcSeq)).isEqualTo("Y");
        Map<String, Object> log = db.queryForMap("SELECT * FROM fds_action_log WHERE tgt_dtc_seq = ? AND act_dv_cd = 'CHECK'",
                dtcSeq);
        assertThat(log).containsEntry("act_actor_dv_cd", "ADMIN").containsEntry("act_rsn_cont", "본인 확인 완료");
        assertThat(((Number) log.get("proc_mbr_no")).longValue()).isEqualTo(adminMember.mbrNo());
        assertThat(log.get("tgt_acnt_no")).isNull();

        mockMvc.perform(checkRequest(admin, dtcSeq, "다시 확인"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CHECKED"));
        assertThat(checkLogCount(dtcSeq)).isEqualTo(1);

        mockMvc.perform(checkRequest(admin, 999_999_999_999L, "없는 기록"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DETECTION_NOT_FOUND"));
    }

    @Test
    @DisplayName("확인 메모가 없거나 공백뿐이거나 500자를 넘으면 400이고 아무것도 바뀌지 않는다. 500자는 받는다")
    void checkRequiresReason() throws Exception {
        Trader t = newTraderAt(DETECT_TIME);
        long dtcSeq = pendingWithdrawDetection(t);
        Tokens admin = loginAsAdmin(createAdmin());

        for (String bad : new String[] {null, "   ", "가".repeat(501)}) {
            mockMvc.perform(checkRequest(admin, dtcSeq, bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertThat(chkYn(dtcSeq)).isEqualTo("N");
        assertThat(checkLogCount(dtcSeq)).isZero();

        mockMvc.perform(checkRequest(admin, dtcSeq, "가".repeat(500))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("관리자 둘 이상이 같은 탐지를 동시에 확인해도 한 번만 성공(나머지 409)하고 CHECK 이력은 하나다")
    void concurrentChecksSucceedOnce() throws Exception {
        Trader t = newTraderAt(DETECT_TIME);
        long dtcSeq = pendingWithdrawDetection(t);
        Tokens admin = loginAsAdmin(createAdmin());

        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            calls.add(() -> mockMvc.perform(checkRequest(admin, dtcSeq, "동시 확인")).andReturn());
        }
        List<MvcResult> results = concurrently(calls);

        assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 200).count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 409).count()).isEqualTo(4);
        assertThat(checkLogCount(dtcSeq)).isEqualTo(1);
    }

    @Test
    @DisplayName("일반 사용자는 탐지 API를 쓸 수 없다(403 ACCESS_DENIED)")
    void userCannotUseDetectionApi() throws Exception {
        Trader t = newTraderAt(DETECT_TIME);
        long dtcSeq = pendingWithdrawDetection(t);

        mockMvc.perform(get(DETECTIONS).header("Authorization", bearer(t.tokens())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(checkRequest(t.tokens(), dtcSeq, "사용자"))
                .andExpect(status().isForbidden());
        assertThat(chkYn(dtcSeq)).isEqualTo("N");
    }
}
