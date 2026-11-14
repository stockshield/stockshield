package com.stockshield.withdraw;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stockshield.common.error.ApiException;
import com.stockshield.common.error.ErrorCode;
import com.stockshield.fds.api.FdsService;
import com.stockshield.fds.api.LockedAccount;
import com.stockshield.fds.api.WithdrawCheckResult;
import com.stockshield.fds.api.WithdrawCommand;
import com.stockshield.ledger.Account;
import com.stockshield.ledger.AccountLocker;
import com.stockshield.ledger.FdsActionLog;
import com.stockshield.ledger.FdsActionLogRepository;
import com.stockshield.ledger.LedgerNumbers;

/**
 * 출금의 원장 트랜잭션 (TRD_WDR_001 #2~#4, FDS_WDR_001 #1~#4, 연결 합의서 6절).
 * 계좌 락 → 멱등키 재확인 → 계좌 상태 → FDS 판단 → 계좌·예수금 변경과 출금 저장 → FDS 탐지 기록 → 잠금 이력.
 *
 * - FDS는 판단만 돌려주고, 계좌(오류 횟수·상태·예수금)와 조치 이력은 여기서만 바꿈.
 * - 업무 거절은 예외로 되돌리지 않고 REJECTED로 커밋함. 오류 횟수 증가와 잠금도 그 거절과 같은 커밋에 들어감 (TRD_LED_001 #3).
 * - FDS가 예외를 던지면(DB 장애로 누적액·비밀번호를 확인할 수 없음) 잡지 않고 전부 롤백함. 확인할 수 없으면 출금하지 않음 (FDS_WDR_001 #4).
 */
@Service
public class WithdrawalLedger {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalLedger.class);

    private final AccountLocker accountLocker;
    private final WithdrawalRepository withdrawals;
    private final FdsActionLogRepository actionLogs;
    private final LedgerNumbers numbers;
    private final FdsService fdsService;

    public WithdrawalLedger(AccountLocker accountLocker, WithdrawalRepository withdrawals,
                            FdsActionLogRepository actionLogs, LedgerNumbers numbers, FdsService fdsService) {
        this.accountLocker = accountLocker;
        this.withdrawals = withdrawals;
        this.actionLogs = actionLogs;
        this.numbers = numbers;
        this.fdsService = fdsService;
    }

    /** @param trcId 이 요청의 거래추적번호 */
    @Transactional
    public WithdrawalReply withdraw(long mbrNo, WithdrawCommand cmd, String trcId) {
        Account account = accountLocker.lockByMbrNo(mbrNo)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        String acntNo = account.getAcntNo();

        Optional<Withdrawal> existing = withdrawals.findByAcntNoAndIdmpKey(acntNo, cmd.idmpKey());
        if (existing.isPresent()) {
            return replay(existing.get(), cmd);
        }
        // 잠긴 계좌는 FDS를 부르지 않음. 428 대기 중이던 키도 같음(대기 기록은 만료 정리가 닫음, 문서 수정 대기 목록 #32)
        if (!account.isActive()) {
            return WithdrawalReply.of(save(acntNo, cmd, ErrorCode.ACCOUNT_LOCKED, null, null, trcId), false);
        }

        LockedAccount locked = new LockedAccount(acntNo, account.getMbrNo(), account.getWdrPwd(),
                account.getWdrPwdFailCnt());
        // 24시간 누적 판단과 비밀번호 확인이 락 안에서 일어나, 동시 분할 출금이 서로를 못 보고 함께 통과하지 못함
        WithdrawCheckResult check = fdsService.checkWithdrawal(locked, cmd);

        switch (check.decision()) {
            case CONTENT_CHANGED:
                // 428 뒤 같은 키로 금액·받는 은행·받는 계좌를 바꿔 보냄. 아무것도 저장하지 않음
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            case PASSWORD_REQUIRED:
                // 기존 대기 기록을 이어 쓰는 재요청(dtcSeq 있음)이면 탐지 기록·경보를 다시 만들지 않음
                if (check.dtcSeq() == null) {
                    fdsService.recordWithdrawal(locked, cmd, check, null);
                }
                return WithdrawalReply.passwordRequired(check, trcId);
            case NOT_LARGE:
                // 대량 출금이 아니면 보낸 비밀번호는 확인하지 않았으므로 오류 횟수도 그대로
                return WithdrawalReply.of(payOut(account, cmd, null, trcId), false);
            case PASSED: {
                // 예수금보다 인증이 먼저라, 이후 예수금 부족으로 거절돼도 오류 횟수는 0으로 남음
                account.resetWdrPwdFailCnt();
                Withdrawal w = payOut(account, cmd, 0, trcId);
                fdsService.recordWithdrawal(locked, cmd, check, w.getWdrNo());
                return WithdrawalReply.of(w, false);
            }
            case MISMATCH: {
                account.recordWdrPwdFail(check.failCntAfter());
                Withdrawal w = save(acntNo, cmd, ErrorCode.WDR_PWD_MISMATCH, check.failCntAfter(), null, trcId);
                fdsService.recordWithdrawal(locked, cmd, check, w.getWdrNo());
                return WithdrawalReply.of(w, false);
            }
            case LOCKED: {
                account.tempLock(check.failCntAfter());
                Withdrawal w = save(acntNo, cmd, ErrorCode.WDR_PWD_LOCKED, check.failCntAfter(), null, trcId);
                long dtcSeq = fdsService.recordWithdrawal(locked, cmd, check, w.getWdrNo());
                // 잠금과 그 원인인 탐지 기록을 이음. 긴급 경보는 FDS가 커밋 뒤 발행함
                actionLogs.saveAndFlush(FdsActionLog.tempLock(acntNo, dtcSeq));
                return WithdrawalReply.of(w, false);
            }
            default:
                throw new IllegalStateException("알 수 없는 FDS 판단 " + check.decision());
        }
    }

    /** 예수금이 모자라면 거절, 아니면 차감하고 완료 */
    private Withdrawal payOut(Account account, WithdrawCommand cmd, Integer postPwdFailCnt, String trcId) {
        if (account.getDpsBal() < cmd.wdrAmt()) {
            return save(account.getAcntNo(), cmd, ErrorCode.INSUFFICIENT_BALANCE, postPwdFailCnt, null, trcId);
        }
        account.decreaseDpsBal(cmd.wdrAmt());
        return save(account.getAcntNo(), cmd, null, postPwdFailCnt, account.getDpsBal(), trcId);
    }

    /**
     * 계좌 변경(예수금·오류 횟수·상태)도 여기서 함께 나감. 출금 행이 먼저 있어야 FDS가 fds_detection.wdr_no로 참조할 수 있음.
     * UNIQUE(acnt_no, idmp_key)는 최후 방어선이고, 위반은 시스템 오류로 보고 그대로 던짐(전부 롤백, 500).
     */
    private Withdrawal save(String acntNo, WithdrawCommand cmd, ErrorCode reject, Integer postPwdFailCnt,
                            Long postDpsBal, String trcId) {
        return withdrawals.saveAndFlush(Withdrawal.of(numbers.nextWdrNo(), acntNo, cmd,
                reject == null ? null : reject.name(), postPwdFailCnt, postDpsBal, trcId));
    }

    private WithdrawalReply replay(Withdrawal w, WithdrawCommand cmd) {
        if (!w.sameContent(cmd)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        // 로그의 traceId(MDC)는 이번 요청 번호라 한 줄에 두 번호가 남음 (SYS_SEC_001 #6)
        log.info("같은 멱등키 재요청 wdrNo={} 처음 요청 traceId={}", w.getWdrNo(), w.getTrcId());
        return WithdrawalReply.of(w, true);
    }
}
