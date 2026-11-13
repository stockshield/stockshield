package com.stockshield.fds;

import org.springframework.stereotype.Service;

import com.stockshield.fds.access.LoginAccessChecker;
import com.stockshield.fds.api.CheckResult;
import com.stockshield.fds.api.ConfirmResult;
import com.stockshield.fds.api.FdsService;
import com.stockshield.fds.api.LockedAccount;
import com.stockshield.fds.api.LoginAccessCommand;
import com.stockshield.fds.api.LoginAccessResult;
import com.stockshield.fds.api.RequestMeta;
import com.stockshield.fds.api.WithdrawCheckResult;
import com.stockshield.fds.api.WithdrawCommand;
import com.stockshield.fds.detection.DetectionRecorder;
import com.stockshield.fds.withdraw.WithdrawLimitChecker;

/**
 * 원장·회원이 부르는 FDS 입구의 실제 구현. 각 규칙의 컴포넌트로 넘기기만 한다.
 *
 * 트랜잭션 전파는 FdsService 인터페이스에 선언되어 있다. 여기 메서드에는 @Transactional을 붙이지 않는다
 * (붙이면 인터페이스의 MANDATORY·REQUIRES_NEW 선언이 무시된다, 연결 합의서 v0.0.8 2절).
 */
@Service
public class FdsServiceImpl implements FdsService {

    private final LoginAccessChecker loginAccessChecker;
    private final WithdrawLimitChecker withdrawLimitChecker;
    private final DetectionRecorder detectionRecorder;

    public FdsServiceImpl(LoginAccessChecker loginAccessChecker, WithdrawLimitChecker withdrawLimitChecker,
                          DetectionRecorder detectionRecorder) {
        this.loginAccessChecker = loginAccessChecker;
        this.withdrawLimitChecker = withdrawLimitChecker;
        this.detectionRecorder = detectionRecorder;
    }

    // ---------------------------------------------------------------- FDS ① 새 접속

    @Override
    public LoginAccessResult checkLoginAccess(LoginAccessCommand cmd) {
        return loginAccessChecker.check(cmd);
    }

    @Override
    public ConfirmResult confirmLoginAccess(long dtcSeq) {
        return loginAccessChecker.confirm(dtcSeq);
    }

    @Override
    public void failLoginAccess(long dtcSeq) {
        loginAccessChecker.fail(dtcSeq);
    }

    @Override
    public void registerSignupAccess(long mbrNo, RequestMeta meta) {
        loginAccessChecker.registerSignup(mbrNo, meta);
    }

    // ---------------------------------------------------------------- FDS ② 대량 출금

    @Override
    public WithdrawCheckResult checkWithdrawal(LockedAccount account, WithdrawCommand cmd) {
        return withdrawLimitChecker.check(account, cmd);
    }

    @Override
    public long recordWithdrawal(LockedAccount account, WithdrawCommand cmd, WithdrawCheckResult check, String wdrNo) {
        return withdrawLimitChecker.record(account, cmd, check, wdrNo);
    }

    // ---------------------------------------------------------------- 관리자 확인

    @Override
    public CheckResult markChecked(long dtcSeq) {
        return detectionRecorder.markChecked(dtcSeq);
    }
}
