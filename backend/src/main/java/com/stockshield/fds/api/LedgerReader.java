package com.stockshield.fds.api;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * FDS가 원장(withdrawal·orders·trade_history)을 읽는 유일한 입구. 구현은 조성우(원장).
 * 전파는 여기서 선언하고 구현 메서드에는 @Transactional을 붙이지 않는다.
 * (FDS_WDR_001·FDS_TRD_001 구현방안, 합의서 4절·7절)
 */
public interface LedgerReader {

    /** 호출자(출금) 트랜잭션 안에서 since 이후(포함) DONE 출금 합계 (FDS_WDR_001 #1) */
    @Transactional(propagation = Propagation.MANDATORY)
    long sumDoneWithdrawalSince(String acntNo, LocalDateTime since);

    /** ③ 리스너의 새 트랜잭션 안에서 부른다. FILLED 주문만 (FDS_TRD_001 #2) */
    @Transactional(readOnly = true)
    Optional<FilledOrderView> findFilledOrder(String ordNo);
}
