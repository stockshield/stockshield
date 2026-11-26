package com.stockshield.fds.withdraw;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.stockshield.fds.api.LedgerReader;
import com.stockshield.fds.api.LockedAccount;
import com.stockshield.fds.api.WithdrawCheckResult;
import com.stockshield.fds.api.WithdrawCheckResult.Decision;
import com.stockshield.fds.api.WithdrawCheckResult.LargeReason;
import com.stockshield.fds.api.WithdrawCommand;
import com.stockshield.fds.detection.DetectionRecorder;
import com.stockshield.fds.detection.DetectionRecorder.NewDetection;
import com.stockshield.fds.detection.DetectionResult;
import com.stockshield.fds.detection.DetectionRule;
import com.stockshield.fds.detection.PendingDetection;

/**
 * FDS ② 대량 출금 추가 인증 (FDS_WDR_001, 연결 합의서 v0.0.8 6절).
 *
 * 원장 트랜잭션(계좌 락을 잡은 상태) 안에서 두 번 불린다.
 *  1) check: 원장 반영 전 판단. 계좌는 다시 읽지 않고 원장이 락으로 읽어 넘긴 값만 쓴다.
 *  2) record: withdrawal 저장 뒤 탐지 기록 저장·결과 변경 (fds_detection.wdr_no가 withdrawal을 참조하므로 뒤에 한다).
 * 판단 결과만 돌려주고 계좌 상태·오류 횟수·fds_action_log는 바꾸지 않는다(원장이 바꾼다, 합의 5-2·12).
 * DB만 쓰고 Redis·메일·외부 HTTP·Kafka를 부르지 않으며 새 트랜잭션도 열지 않는다.
 */
@Component
public class WithdrawLimitChecker {

    /** 5회 연속 틀리면 잠금 (FDS_WDR_001 #3) */
    public static final int MAX_PWD_FAIL = 5;
    static final Duration DAILY_WINDOW = Duration.ofHours(24);

    private final LedgerReader ledgerReader;
    private final PasswordEncoder passwordEncoder;
    private final DetectionRecorder recorder;
    private final Clock clock;
    private final long threshold;

    public WithdrawLimitChecker(LedgerReader ledgerReader, PasswordEncoder passwordEncoder, DetectionRecorder recorder,
                                Clock clock,
                                @Value("${stockshield.fds.withdraw.large-threshold:5000000}") long threshold) {
        this.ledgerReader = ledgerReader;
        this.passwordEncoder = passwordEncoder;
        this.recorder = recorder;
        this.clock = clock;
        this.threshold = threshold;
    }

    public WithdrawCheckResult check(LockedAccount account, WithdrawCommand cmd) {
        Optional<PendingDetection> pending = recorder.lockPendingWithdraw(account.acntNo(), cmd.idmpKey());
        if (pending.isPresent()) {
            PendingDetection p = pending.get();
            if (recorder.isPendingValid(p.regTs())) {
                return checkRetry(account, cmd, p);
            }
            // 5분이 지난 대기 기록은 여기서 FAIL로 닫고 새 요청으로 판단한다(합의 11-3)
            recorder.changePendingResult(p.dtcSeq(), DetectionResult.FAIL, null);
        }
        return checkNew(account, cmd);
    }

    /** 428 뒤 같은 키로 다시 온 요청: 내용이 같아야 하고, 비밀번호가 있으면 확인한다 */
    private WithdrawCheckResult checkRetry(LockedAccount account, WithdrawCommand cmd, PendingDetection p) {
        if (!WithdrawRequestHash.of(cmd).equals(p.reqHash())) {
            return new WithdrawCheckResult(Decision.CONTENT_CHANGED, null, 0L, threshold, null, p.dtcSeq());
        }
        long dailySum = dailySum(account);
        LargeReason reason = reason(cmd.wdrAmt(), dailySum);
        if (cmd.wdrPwd() == null) {
            // 기존 대기 기록을 그대로 두고 다시 428 (합의 13)
            return new WithdrawCheckResult(Decision.PASSWORD_REQUIRED, reason, dailySum, threshold, null, p.dtcSeq());
        }
        return verifyPassword(account, cmd, reason, dailySum, p.dtcSeq());
    }

    private WithdrawCheckResult checkNew(LockedAccount account, WithdrawCommand cmd) {
        long dailySum = dailySum(account);
        if (!isLarge(cmd.wdrAmt(), dailySum)) {
            // 대량 출금이 아니면 보낸 비밀번호는 확인하지 않는다(FDS_WDR_001 #2)
            return new WithdrawCheckResult(Decision.NOT_LARGE, null, dailySum, threshold, null, null);
        }
        LargeReason reason = reason(cmd.wdrAmt(), dailySum);
        if (cmd.wdrPwd() == null) {
            return new WithdrawCheckResult(Decision.PASSWORD_REQUIRED, reason, dailySum, threshold, null, null);
        }
        return verifyPassword(account, cmd, reason, dailySum, null);
    }

    /** 맞으면 PASSED(원장이 오류 횟수를 0으로), 틀리면 +1, 5가 되면 LOCKED (합의 12) */
    private WithdrawCheckResult verifyPassword(LockedAccount account, WithdrawCommand cmd, LargeReason reason,
                                               long dailySum, Long dtcSeq) {
        if (passwordEncoder.matches(cmd.wdrPwd(), account.wdrPwdHash())) {
            return new WithdrawCheckResult(Decision.PASSED, reason, dailySum, threshold, null, dtcSeq);
        }
        int failCntAfter = Math.min(account.wdrPwdFailCnt() + 1, MAX_PWD_FAIL);
        Decision decision = failCntAfter >= MAX_PWD_FAIL ? Decision.LOCKED : Decision.MISMATCH;
        return new WithdrawCheckResult(decision, reason, dailySum, threshold, failCntAfter, dtcSeq);
    }

    /**
     * 탐지 기록 저장·결과 변경. 저장하거나 결과를 바꾼 탐지 기록의 dtcSeq를 돌려준다.
     * PASSED·MISMATCH·LOCKED와, 새 PENDING을 만드는 PASSWORD_REQUIRED(check.dtcSeq == null)일 때만 불린다.
     */
    public long record(LockedAccount account, WithdrawCommand cmd, WithdrawCheckResult check, String wdrNo) {
        Decision decision = check.decision();
        if (decision == Decision.PASSWORD_REQUIRED) {
            if (check.dtcSeq() != null) {
                throw new IllegalArgumentException("기존 대기 기록을 이어 쓰는 428에는 recordWithdrawal을 부르지 않는다");
            }
            return recorder.insert(newDetection(account, cmd, check, DetectionResult.PENDING, null));
        }
        DetectionResult result = switch (decision) {
            case PASSED -> DetectionResult.PASS;
            case MISMATCH -> DetectionResult.FAIL;
            case LOCKED -> DetectionResult.LOCKED;
            default -> throw new IllegalArgumentException(decision + "에는 recordWithdrawal을 부르지 않는다");
        };
        if (wdrNo == null) {
            throw new IllegalArgumentException(decision + "는 저장된 출금번호가 있어야 한다");
        }
        if (check.dtcSeq() != null) {
            // 428 뒤 재요청: check에서 행을 잡아 두었으므로 정상이라면 반드시 바뀐다(0건이면 시스템 오류, 합의 11-3)
            if (!recorder.changePendingResult(check.dtcSeq(), result, wdrNo)) {
                throw new IllegalStateException("인증 대기 기록의 결과를 바꾸지 못했습니다 dtcSeq=" + check.dtcSeq());
            }
            return check.dtcSeq();
        }
        // 처음부터 비밀번호를 붙인 요청: 결과로 바로 저장(FDS_WDR_001 구현방안 '기록')
        return recorder.insert(newDetection(account, cmd, check, result, wdrNo));
    }

    private NewDetection newDetection(LockedAccount account, WithdrawCommand cmd, WithdrawCheckResult check,
                                      DetectionResult result, String wdrNo) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("wdrAmt", cmd.wdrAmt());
        detail.put("dailySum", check.dailySum());
        detail.put("threshold", check.threshold());
        detail.put("reason", check.reason() == null ? null : check.reason().name());
        detail.put("pwdProvided", cmd.wdrPwd() != null);
        if (check.failCntAfter() != null) {
            detail.put("failCntAfter", check.failCntAfter());
        }
        detail.put("reqHash", WithdrawRequestHash.of(cmd));
        return new NewDetection(DetectionRule.WITHDRAW_LARGE, account.mbrNo(), account.acntNo(), null, wdrNo,
                cmd.idmpKey(), cmd.meta(), result, detail);
    }

    /** 이번 금액 ≥ 기준, 또는 24시간 DONE 합계 + 이번 금액 ≥ 기준 (FDS_WDR_001 #1) */
    private boolean isLarge(long amount, long dailySum) {
        return amount >= threshold || dailySum + amount >= threshold;
    }

    private LargeReason reason(long amount, long dailySum) {
        return amount >= threshold ? LargeReason.SINGLE : LargeReason.DAILY_SUM;
    }

    /** 이번 금액을 뺀 24시간 DONE 합계. 경계 시각(현재 − 24시간)도 포함한다 */
    private long dailySum(LockedAccount account) {
        LocalDateTime since = LocalDateTime.now(clock).minus(DAILY_WINDOW);
        return ledgerReader.sumDoneWithdrawalSince(account.acntNo(), since);
    }
}
