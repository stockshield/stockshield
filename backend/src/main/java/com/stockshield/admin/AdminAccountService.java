package com.stockshield.admin;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stockshield.common.error.ApiException;
import com.stockshield.common.error.ErrorCode;
import com.stockshield.ledger.Account;
import com.stockshield.ledger.AccountLocker;
import com.stockshield.ledger.FdsActionLog;
import com.stockshield.ledger.FdsActionLogRepository;

/**
 * 관리자 계좌 잠금·해제 (FDS_ALT_001 #4, API 명세서 'API_명세서' 27행). FDS는 부르지 않음 (연결 합의서 8절).
 * 주문·출금과 같은 계좌 락 안에서 바꿔 진행 중인 거래와 순서가 섞이지 않음 (TRD_LED_001 #2).
 */
@Service
public class AdminAccountService {

    private final AccountLocker accountLocker;
    private final FdsActionLogRepository actionLogs;

    public AdminAccountService(AccountLocker accountLocker, FdsActionLogRepository actionLogs) {
        this.accountLocker = accountLocker;
        this.actionLogs = actionLogs;
    }

    /** @param acntStatCd ACTIVE(해제) 또는 LOCKED(잠금) */
    @Transactional
    public Map<String, Object> changeStatus(long adminMbrNo, String acntNo, String acntStatCd, String actRsnCont) {
        if (!Account.ACTIVE.equals(acntStatCd) && !Account.LOCKED.equals(acntStatCd)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        String reason = ActionReasons.normalize(actRsnCont);
        // 상태는 락을 잡은 뒤에 읽음. 먼저 읽으면 진행 중인 출금이 TEMP_LOCKED로 바꾸기 전 값으로 판단함
        Account account = accountLocker.lockByAcntNo(acntNo)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (acntStatCd.equals(account.getAcntStatCd())) {
            throw new ApiException(ErrorCode.SAME_STATUS);
        }
        if (Account.ACTIVE.equals(acntStatCd)) {
            account.unlock();
            actionLogs.saveAndFlush(FdsActionLog.accountStatus("UNLOCK", acntNo, adminMbrNo, reason));
        } else {
            account.adminLock();
            actionLogs.saveAndFlush(FdsActionLog.accountStatus("LOCK", acntNo, adminMbrNo, reason));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("acntNo", acntNo);
        body.put("acntStatCd", account.getAcntStatCd());
        return body;
    }
}
