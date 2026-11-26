package com.stockshield.withdraw;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.stockshield.common.error.ApiException;
import com.stockshield.common.error.ErrorCode;
import com.stockshield.common.trace.TraceIds;
import com.stockshield.common.web.KstTime;
import com.stockshield.common.web.PageBody;
import com.stockshield.fds.api.RequestMeta;
import com.stockshield.fds.api.WithdrawCommand;
import com.stockshield.ledger.Account;
import com.stockshield.ledger.AccountRepository;
import com.stockshield.ledger.IdempotencyKeys;

/**
 * 출금 접수(입력 검증·정규화 뒤 원장 트랜잭션으로 넘김)와 출금 내역 조회.
 * 출금은 Redis를 쓰지 않음 (SYS_CORE_002 #5).
 */
@Service
public class WithdrawalService {

    static final int RCV_BANK_NM_MAX = 50;
    private static final Pattern RCV_ACNT_NO = Pattern.compile("^[0-9-]+$");
    private static final Pattern WDR_PWD = Pattern.compile("^[0-9]{6}$");
    /** 같은 시각이면 나중에 채번된 번호가 먼저 */
    private static final Sort LATEST_FIRST = Sort.by(Sort.Order.desc("regTs"), Sort.Order.desc("wdrNo"));

    private final WithdrawalLedger withdrawalLedger;
    private final WithdrawalRepository withdrawals;
    private final AccountRepository accounts;

    public WithdrawalService(WithdrawalLedger withdrawalLedger, WithdrawalRepository withdrawals,
                             AccountRepository accounts) {
        this.withdrawalLedger = withdrawalLedger;
        this.withdrawals = withdrawals;
        this.accounts = accounts;
    }

    public WithdrawalReply withdraw(long mbrNo, WithdrawalController.WithdrawRequest request, RequestMeta meta) {
        return withdrawalLedger.withdraw(mbrNo, normalize(request, meta), TraceIds.current());
    }

    /**
     * 틀리면 400이고 출금 비밀번호 오류 횟수에 넣지 않음 (TRD_WDR_001 #1, FDS_WDR_001 #2).
     * 받는 은행은 앞뒤 공백을, 받는 계좌는 하이픈을 지운 값을 저장·내용 비교·FDS 해시에 함께 씀.
     * 공백·하이픈만 다른 같은 요청이 409가 되지 않게 하기 위해서임 (연결 합의서 6절, 문서 수정 대기 목록 #52).
     */
    static WithdrawCommand normalize(WithdrawalController.WithdrawRequest r, RequestMeta meta) {
        if (r == null || r.wdrAmt() == null || r.wdrAmt() < 1 || r.rcvBankNm() == null || r.rcvAcntNo() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        String rcvBankNm = r.rcvBankNm().strip();
        if (rcvBankNm.isEmpty() || rcvBankNm.codePointCount(0, rcvBankNm.length()) > RCV_BANK_NM_MAX) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        if (!RCV_ACNT_NO.matcher(r.rcvAcntNo()).matches()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        String rcvAcntNo = r.rcvAcntNo().replace("-", "");
        if (rcvAcntNo.length() < 10 || rcvAcntNo.length() > 16) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        if (r.wdrPwd() != null && !WDR_PWD.matcher(r.wdrPwd()).matches()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        return new WithdrawCommand(r.wdrAmt(), rcvBankNm, rcvAcntNo, IdempotencyKeys.normalize(r.idmpKey()),
                r.wdrPwd(), meta);
    }

    /** 출금 내역(완료·거절) 최신순 (TRD_ACC_001 #4, API 명세서 'API_명세서' 22행) */
    public PageBody history(long mbrNo, int page, int size) {
        Account account = accounts.findByMbrNo(mbrNo)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        Page<Withdrawal> found = withdrawals.findByAcntNo(account.getAcntNo(),
                PageBody.request(page, size, LATEST_FIRST));
        List<Map<String, Object>> content = found.getContent().stream().map(WithdrawalService::historyItem).toList();
        return PageBody.of(found, content);
    }

    private static Map<String, Object> historyItem(Withdrawal w) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("wdrNo", w.getWdrNo());
        item.put("regTs", KstTime.format(w.getRegTs()));
        item.put("wdrAmt", w.getWdrAmt());
        item.put("rcvBankNm", w.getRcvBankNm());
        item.put("mskRcvAcntNo", maskAcntNo(w.getRcvAcntNo()));
        item.put("addAuthYn", w.getAddAuthYn());
        item.put("wdrStatCd", w.getWdrStatCd());
        item.put("rjctRsnCd", w.getRjctRsnCd());
        item.put("rjctRsnMsg", w.getRjctRsnCd() == null ? null : ErrorCode.valueOf(w.getRjctRsnCd()).message());
        item.put("postDpsBal", w.getPostDpsBal());
        return item;
    }

    /** 뒷 4자리만 보임 (예: ******1234) */
    static String maskAcntNo(String rcvAcntNo) {
        int visibleFrom = Math.max(rcvAcntNo.length() - 4, 0);
        return "*".repeat(visibleFrom) + rcvAcntNo.substring(visibleFrom);
    }
}
