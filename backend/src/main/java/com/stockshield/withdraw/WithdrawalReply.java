package com.stockshield.withdraw;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

import com.stockshield.common.error.ErrorCode;
import com.stockshield.common.error.ErrorResponse;
import com.stockshield.common.web.KstTime;
import com.stockshield.fds.api.WithdrawCheckResult;

/**
 * 출금 응답. 처음 응답과 같은 키 재요청 응답을 저장된 withdrawal 행만으로 같은 코드에서 만듦
 * (API 명세서 '공통 규칙' 46행, 요구사항명세서 부록 #16).
 *
 * @param replayed 같은 키 재요청이면 true (응답 헤더 Idempotency-Replayed)
 */
public record WithdrawalReply(HttpStatus status, Object body, boolean replayed) {

    static final int MAX_PWD_FAIL = 5;

    static WithdrawalReply of(Withdrawal w, boolean replayed) {
        if (!w.isDone()) {
            ErrorCode code = ErrorCode.valueOf(w.getRjctRsnCd());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("wdrNo", w.getWdrNo());
            detail.put("wdrStatCd", w.getWdrStatCd());
            if (code == ErrorCode.WDR_PWD_MISMATCH) {
                detail.put("remainCnt", MAX_PWD_FAIL - w.getPostPwdFailCnt());
            }
            // 본문 traceId는 결과를 처음 만든 요청의 번호 (SYS_SEC_001 #6)
            return new WithdrawalReply(code.status(), ErrorResponse.of(code, detail, w.getTrcId()), replayed);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("wdrNo", w.getWdrNo());
        body.put("wdrAmt", w.getWdrAmt());
        body.put("dpsBal", w.getPostDpsBal());
        body.put("addAuthYn", w.getAddAuthYn());
        body.put("wdrStatCd", w.getWdrStatCd());
        body.put("regTs", KstTime.format(w.getRegTs()));
        return new WithdrawalReply(HttpStatus.CREATED, body, replayed);
    }

    /** 428: 출금은 저장하지 않음. dailySum은 이번 금액을 뺀 24시간 DONE 합계로, FDS 결과를 그대로 옮김 */
    static WithdrawalReply passwordRequired(WithdrawCheckResult check, String trcId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", check.reason().name());
        detail.put("dailySum", check.dailySum());
        detail.put("threshold", check.threshold());
        ErrorCode code = ErrorCode.WDR_PWD_REQUIRED;
        return new WithdrawalReply(code.status(), ErrorResponse.of(code, detail, trcId), false);
    }
}
