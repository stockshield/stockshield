package com.stockshield.fds.api;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 원장·회원이 FDS를 부르는 유일한 입구. 판단만 하고 account는 바꾸지 않는다.
 * 전파는 여기서 선언한다. 구현 클래스와 가짜 구현의 메서드에는 @Transactional을 붙이지 않는다(붙이면 이 선언이 무시된다).
 *
 * 연결 합의서 v0.0.8 10절. FDS_LGN_001, FDS_WDR_001, FDS_LOG_001, USR_AUTH_001 #4
 */
public interface FdsService {

    /** 돌아올 때 커밋되어 있다 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    LoginAccessResult checkLoginAccess(LoginAccessCommand cmd);

    /** PENDING이 아니거나 5분이 지났으면(reg_ts <= 현재 − 5분) EXPIRED */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ConfirmResult confirmLoginAccess(long dtcSeq);

    /** PENDING이 아니면 아무것도 하지 않는다 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void failLoginAccess(long dtcSeq);

    /** 가입 트랜잭션 안에서만 */
    @Transactional(propagation = Propagation.MANDATORY)
    void registerSignupAccess(long mbrNo, RequestMeta meta);

    /** 계좌 락을 잡은 원장 트랜잭션 안에서만. 재요청이면 PENDING 행을 FOR NO KEY UPDATE로 잡는다 */
    @Transactional(propagation = Propagation.MANDATORY)
    WithdrawCheckResult checkWithdrawal(LockedAccount account, WithdrawCommand cmd);

    /**
     * 저장하거나 결과를 바꾼 탐지 기록의 dtcSeq. 428이면 wdrNo = null.
     * PASSED·MISMATCH·LOCKED와, 새 PENDING을 만드는 PASSWORD_REQUIRED(check.dtcSeq == null)일 때만 부른다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    long recordWithdrawal(LockedAccount account, WithdrawCommand cmd,
                          WithdrawCheckResult check, String wdrNo);

    /** 관리자 확인 트랜잭션 안에서만 */
    @Transactional(propagation = Propagation.MANDATORY)
    CheckResult markChecked(long dtcSeq);
}
