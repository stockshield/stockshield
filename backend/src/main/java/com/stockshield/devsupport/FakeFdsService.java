package com.stockshield.devsupport;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import com.stockshield.fds.api.CheckResult;
import com.stockshield.fds.api.ConfirmResult;
import com.stockshield.fds.api.FdsService;
import com.stockshield.fds.api.LockedAccount;
import com.stockshield.fds.api.LoginAccessCommand;
import com.stockshield.fds.api.LoginAccessResult;
import com.stockshield.fds.api.RequestMeta;
import com.stockshield.fds.api.WithdrawCheckResult;
import com.stockshield.fds.api.WithdrawCheckResult.Decision;
import com.stockshield.fds.api.WithdrawCheckResult.LargeReason;
import com.stockshield.fds.api.WithdrawCommand;

/**
 * 실제 FDS가 develop에 들어오기 전(11/16)까지 앱을 띄우기 위한 가짜. fake-fds 프로필을 켤 때만 등록된다.
 * 로그인은 항상 KNOWN, 출금은 기준 금액 이상이면 비밀번호가 없을 때 428(PASSWORD_REQUIRED)·있을 때 통과(비교하지 않음),
 * 그 밖은 NOT_LARGE. 24시간 합계는 보지 않고 탐지 기록도 쓰지 않는다.
 * 실제 FDS가 merge된 뒤 정리 PR이 들어가기 전까지 빈이 둘이 되지 않게 @Primary를 붙인다. prod 프로필과 함께 켜면 기동을 멈춘다.
 * 전파는 FdsService 인터페이스의 선언을 따르므로, 이 가짜로 돌려도 MANDATORY 메서드를 트랜잭션 밖에서 부르면 실패한다.
 * (연결 합의서 v0.0.8 12절. 11/16 조성우 정리 PR에서 삭제)
 */
@Service
@Primary
@Profile("fake-fds")
public class FakeFdsService implements FdsService {

    private static final Logger log = LoggerFactory.getLogger(FakeFdsService.class);

    private final long threshold;

    public FakeFdsService(Environment environment,
                          @Value("${stockshield.fds.withdraw.large-threshold:5000000}") long threshold) {
        if (Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            throw new IllegalStateException("fake-fds 프로필은 prod 프로필과 함께 켤 수 없습니다");
        }
        this.threshold = threshold;
        log.warn("가짜 FdsService(fake-fds)로 기동합니다. 새 접속 인증과 대량 출금 탐지 기록이 동작하지 않습니다");
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
        // 탐지 기록을 쓰지 않는다
    }

    @Override
    public void registerSignupAccess(long mbrNo, RequestMeta meta) {
        // 신뢰 접속을 쓰지 않는다
    }

    @Override
    public WithdrawCheckResult checkWithdrawal(LockedAccount account, WithdrawCommand cmd) {
        if (cmd.wdrAmt() < threshold) {
            return new WithdrawCheckResult(Decision.NOT_LARGE, null, 0L, threshold, null, null);
        }
        Decision decision = cmd.wdrPwd() == null ? Decision.PASSWORD_REQUIRED : Decision.PASSED;
        return new WithdrawCheckResult(decision, LargeReason.SINGLE, 0L, threshold, null, null);
    }

    /** 탐지 기록을 쓰지 않는다. LOCKED를 돌려주지 않으므로 0이 TEMP_LOCK 이력의 tgt_dtc_seq에 들어가지 않는다 */
    @Override
    public long recordWithdrawal(LockedAccount account, WithdrawCommand cmd, WithdrawCheckResult check, String wdrNo) {
        return 0L;
    }

    /** 탐지 기록이 없으므로 NOT_FOUND */
    @Override
    public CheckResult markChecked(long dtcSeq) {
        return CheckResult.NOT_FOUND;
    }
}
