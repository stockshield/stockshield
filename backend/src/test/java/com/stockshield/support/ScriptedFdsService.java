package com.stockshield.support;

import java.util.concurrent.atomic.AtomicReference;

import com.stockshield.fds.api.CheckResult;
import com.stockshield.fds.api.ConfirmResult;
import com.stockshield.fds.api.FdsService;
import com.stockshield.fds.api.LockedAccount;
import com.stockshield.fds.api.LoginAccessCommand;
import com.stockshield.fds.api.LoginAccessResult;
import com.stockshield.fds.api.RequestMeta;
import com.stockshield.fds.api.WithdrawCheckResult;
import com.stockshield.fds.api.WithdrawCommand;

/**
 * 테스트가 결과를 정하는 가짜 FdsService (FakeFdsConfig가 등록).
 * 메서드에 @Transactional을 붙이지 않는다. 전파는 FdsService 인터페이스의 선언을 따른다(합의서 2절).
 *
 * 실제 FDS가 저장소에 있으므로 원장 테스트는 대부분 실제 FDS로 돈다. 이 가짜는 FDS 단계에서 실패를 일으켜
 * 원장이 전부 되돌려지는지 보는 롤백 테스트에만 쓴다.
 */
public class ScriptedFdsService implements FdsService {

    private static final WithdrawCheckResult NOT_LARGE =
            new WithdrawCheckResult(WithdrawCheckResult.Decision.NOT_LARGE, null, 0L, 5_000_000L, null, null);

    private final AtomicReference<RuntimeException> signupFailure = new AtomicReference<>();
    private final AtomicReference<WithdrawCheckResult> withdrawCheck = new AtomicReference<>(NOT_LARGE);
    private final AtomicReference<RuntimeException> checkFailure = new AtomicReference<>();
    private final AtomicReference<RuntimeException> recordFailure = new AtomicReference<>();

    /** 다음 registerSignupAccess 호출부터 이 예외를 던진다(null이면 정상) */
    public void failSignupWith(RuntimeException e) {
        signupFailure.set(e);
    }

    /** 다음 checkWithdrawal 호출부터 이 판단을 돌려준다 */
    public void answerWithdrawCheck(WithdrawCheckResult result) {
        withdrawCheck.set(result);
    }

    /** 다음 checkWithdrawal 호출부터 이 예외를 던진다(null이면 정상). 누적액·비밀번호를 확인할 수 없는 장애를 만든다 */
    public void failCheckWith(RuntimeException e) {
        checkFailure.set(e);
    }

    /** 다음 recordWithdrawal 호출부터 이 예외를 던진다(null이면 정상). 원장이 계좌와 출금 행을 쓴 뒤의 실패를 만든다 */
    public void failRecordWith(RuntimeException e) {
        recordFailure.set(e);
    }

    public void reset() {
        signupFailure.set(null);
        withdrawCheck.set(NOT_LARGE);
        checkFailure.set(null);
        recordFailure.set(null);
    }

    @Override
    public LoginAccessResult checkLoginAccess(LoginAccessCommand cmd) {
        return new LoginAccessResult(LoginAccessResult.Type.KNOWN, null, null);
    }

    @Override
    public ConfirmResult confirmLoginAccess(long dtcSeq) {
        return ConfirmResult.CONFIRMED;
    }

    @Override
    public void failLoginAccess(long dtcSeq) {
        // 기록하지 않는다
    }

    @Override
    public void registerSignupAccess(long mbrNo, RequestMeta meta) {
        RuntimeException e = signupFailure.get();
        if (e != null) {
            throw e;
        }
    }

    @Override
    public WithdrawCheckResult checkWithdrawal(LockedAccount account, WithdrawCommand cmd) {
        RuntimeException e = checkFailure.get();
        if (e != null) {
            throw e;
        }
        return withdrawCheck.get();
    }

    @Override
    public long recordWithdrawal(LockedAccount account, WithdrawCommand cmd, WithdrawCheckResult check, String wdrNo) {
        RuntimeException e = recordFailure.get();
        if (e != null) {
            throw e;
        }
        return 0L;
    }

    @Override
    public CheckResult markChecked(long dtcSeq) {
        return CheckResult.NOT_FOUND;
    }
}
