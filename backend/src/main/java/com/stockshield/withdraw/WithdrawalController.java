package com.stockshield.withdraw;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stockshield.auth.AuthPrincipal;
import com.stockshield.common.web.PageBody;
import com.stockshield.common.web.RequestMetas;

import jakarta.servlet.http.HttpServletRequest;

/** 모의 출금 API (API 명세서 'API_명세서' 21·22행). ROLE_USER만 들어옴(SecurityConfig) */
@RestController
@RequestMapping("/api/v1/accounts/me/withdrawals")
public class WithdrawalController {

    static final String REPLAYED_HEADER = "Idempotency-Replayed";

    /** 출금 비밀번호와 받는 계좌번호는 toString에 나오지 않음 */
    public record WithdrawRequest(Long wdrAmt, String rcvBankNm, String rcvAcntNo, String idmpKey, String wdrPwd) {
        @Override
        public String toString() {
            return "WithdrawRequest[wdrAmt=" + wdrAmt + ", rcvBankNm=" + rcvBankNm + ", idmpKey=" + idmpKey + "]";
        }
    }

    private final WithdrawalService withdrawalService;

    public WithdrawalController(WithdrawalService withdrawalService) {
        this.withdrawalService = withdrawalService;
    }

    /** 완료 201, 비밀번호 필요 428(저장 안 함), 저장된 거절 422·423. 같은 키 재요청이면 처음과 같은 상태·본문에 헤더만 붙음 */
    @PostMapping
    public ResponseEntity<Object> withdraw(@AuthenticationPrincipal AuthPrincipal principal,
                                           @RequestBody WithdrawRequest body, HttpServletRequest request) {
        WithdrawalReply reply = withdrawalService.withdraw(principal.mbrNo(), body,
                RequestMetas.optionalDevice(request));
        ResponseEntity.BodyBuilder response = ResponseEntity.status(reply.status());
        if (reply.replayed()) {
            response.header(REPLAYED_HEADER, "true");
        }
        return response.body(reply.body());
    }

    @GetMapping
    public PageBody history(@AuthenticationPrincipal AuthPrincipal principal,
                            @RequestParam(name = "page", defaultValue = "0") int page,
                            @RequestParam(name = "size", defaultValue = "20") int size) {
        return withdrawalService.history(principal.mbrNo(), page, size);
    }
}
