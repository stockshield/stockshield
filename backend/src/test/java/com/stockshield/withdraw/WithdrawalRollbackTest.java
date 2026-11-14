package com.stockshield.withdraw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;

import com.stockshield.fds.api.FdsService;
import com.stockshield.fds.api.LockedAccount;
import com.stockshield.fds.api.RequestMeta;
import com.stockshield.fds.api.WithdrawCheckResult;
import com.stockshield.fds.api.WithdrawCheckResult.Decision;
import com.stockshield.fds.api.WithdrawCheckResult.LargeReason;
import com.stockshield.fds.api.WithdrawCommand;
import com.stockshield.support.FakeFdsConfig;
import com.stockshield.support.LedgerTestSupport;
import com.stockshield.support.ScriptedFdsService;

/**
 * 출금 트랜잭션의 원자성 (TRD_LED_001 #3·구현방안 '테스트' (4), FDS_WDR_001 #3·#4, 연결 합의서 2절·6절).
 *
 * 원장이 계좌(오류 횟수·상태·예수금)와 출금 행을 쓴 뒤 FDS 탐지 기록 단계에서 실패하게 해야 해서,
 * FDS 결과를 테스트가 정하는 가짜(FakeFdsConfig)를 쓴다. 판단이 맞는지는 실제 FDS로 도는 WithdrawalApiTest가 본다.
 */
@Import(FakeFdsConfig.class)
class WithdrawalRollbackTest extends LedgerTestSupport {

    @Autowired ScriptedFdsService fds;
    @Autowired FdsService fdsService;

    @AfterEach
    void resetFds() {
        fds.reset();
    }

    @Test
    @DisplayName("예수금 차감·오류 횟수 초기화·출금 저장 뒤 탐지 기록에서 실패하면 500이고 모두 처리 전 그대로다. 같은 키로 다시 보내면 처리된다")
    void failureAfterLedgerWriteRollsBackEverything() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        db.update("UPDATE account SET wdr_pwd_fail_cnt = 2 WHERE acnt_no = ?", t.acntNo());
        Map<String, Object> request = withdrawBody(6_000_000, newKey(), TEST_WDR_PWD);
        fds.answerWithdrawCheck(new WithdrawCheckResult(Decision.PASSED, LargeReason.SINGLE, 0L, 5_000_000L, null, null));
        fds.failRecordWith(new IllegalStateException("테스트: 탐지 기록 실패"));

        mockMvc.perform(withdrawRequest(t, request))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(withdrawalCount(t.acntNo())).isZero();
        assertThat(dpsBal(t.acntNo())).isEqualTo(PKG_10M);
        assertThat(wdrPwdFailCnt(t.acntNo())).isEqualTo(2);

        // 결과가 저장되지 않았으므로 멱등키도 소모되지 않았다
        fds.failRecordWith(null);
        mockMvc.perform(withdrawRequest(t, request))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotency-Replayed"))
                .andExpect(jsonPath("$.dpsBal").value(4_000_000));
        assertThat(wdrPwdFailCnt(t.acntNo())).isZero();
    }

    @Test
    @DisplayName("5회째 오류 처리 중 실패하면 계좌 잠금·오류 횟수·거절 출금·TEMP_LOCK 이력 중 어느 것도 남지 않는다(한 커밋)")
    void failureWhileLockingLeavesNothing() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        db.update("UPDATE account SET wdr_pwd_fail_cnt = 4 WHERE acnt_no = ?", t.acntNo());
        fds.answerWithdrawCheck(new WithdrawCheckResult(Decision.LOCKED, LargeReason.SINGLE, 0L, 5_000_000L, 5, null));
        fds.failRecordWith(new IllegalStateException("테스트: 탐지 기록 실패"));

        mockMvc.perform(withdrawRequest(t, withdrawBody(6_000_000, newKey(), "000000")))
                .andExpect(status().isInternalServerError());

        assertThat(acntStatCd(t.acntNo())).isEqualTo("ACTIVE");
        assertThat(wdrPwdFailCnt(t.acntNo())).isEqualTo(4);
        assertThat(withdrawalCount(t.acntNo())).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM fds_action_log WHERE tgt_acnt_no = ?", Integer.class,
                t.acntNo())).isZero();
    }

    @Test
    @DisplayName("FDS가 CONTENT_CHANGED로 판단하면 409이고 아무것도 저장하지 않는다")
    void contentChangedStoresNothing() throws Exception {
        Trader t = newTraderAt(MARKET_OPEN);
        fds.answerWithdrawCheck(new WithdrawCheckResult(Decision.CONTENT_CHANGED, null, 0L, 5_000_000L, null, 1L));

        mockMvc.perform(withdrawRequest(t, withdrawBody(6_000_000, newKey(), TEST_WDR_PWD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(withdrawalCount(t.acntNo())).isZero();
        assertThat(dpsBal(t.acntNo())).isEqualTo(PKG_10M);
    }

    @Test
    @DisplayName("출금 판단·탐지 기록은 원장 트랜잭션 밖에서 부르면 실패한다(MANDATORY). 계좌 락 없이 FDS를 부르는 코드를 막는다")
    void fdsWithdrawMethodsRequireLedgerTransaction() {
        LockedAccount account = new LockedAccount("100000000000", 1L, "hash", 0);
        WithdrawCommand cmd = new WithdrawCommand(1L, "국민은행", "1234567890", newKey(), null,
                new RequestMeta(DEFAULT_IP, null, "-"));

        assertThatThrownBy(() -> fdsService.checkWithdrawal(account, cmd))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> fdsService.recordWithdrawal(account, cmd, null, null))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
