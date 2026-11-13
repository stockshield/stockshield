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
 * 지금은 회원가입 롤백 테스트가 쓰는 registerSignupAccess만 결과를 바꿀 수 있다.
 * 출금 판단(checkWithdrawal·recordWithdrawal)은 PR ②(P6 6-7)에서 LedgerReader로 대량 여부를 판단하도록 채운다.
 */
public class ScriptedFdsService implements FdsService {

    private final AtomicReference<RuntimeException> signupFailure = new AtomicReference<>();

    /** 다음 registerSignupAccess 호출부터 이 예외를 던진다(null이면 정상) */
    public void failSignupWith(RuntimeException e) {
        signupFailure.set(e);
    }

    public void reset() {
        signupFailure.set(null);
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
        return new WithdrawCheckResult(WithdrawCheckResult.Decision.NOT_LARGE, null, 0L, 5_000_000L, null, null);
    }

    @Override
    public long recordWithdrawal(LockedAccount account, WithdrawCommand cmd, WithdrawCheckResult check, String wdrNo) {
        return 0L;
    }

    @Override
    public CheckResult markChecked(long dtcSeq) {
        return CheckResult.NOT_FOUND;
    }
}
