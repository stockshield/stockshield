package com.stockshield.account;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stockshield.auth.AuthPrincipal;
import com.stockshield.common.web.PageBody;

/** 내 계좌 조회 API (API 명세서 'API_명세서' 18~20행). ROLE_USER만 들어옴(SecurityConfig) */
@RestController
@RequestMapping("/api/v1/accounts/me")
public class AccountQueryController {

    private final AccountQueryService accountQueryService;

    public AccountQueryController(AccountQueryService accountQueryService) {
        this.accountQueryService = accountQueryService;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(@AuthenticationPrincipal AuthPrincipal principal) {
        return accountQueryService.summary(principal.mbrNo());
    }

    @GetMapping("/holdings")
    public List<Map<String, Object>> holdings(@AuthenticationPrincipal AuthPrincipal principal) {
        return accountQueryService.holdings(principal.mbrNo());
    }

    @GetMapping("/orders")
    public PageBody orders(@AuthenticationPrincipal AuthPrincipal principal,
                           @RequestParam(name = "page", defaultValue = "0") int page,
                           @RequestParam(name = "size", defaultValue = "20") int size,
                           @RequestParam(name = "ordStatCd", required = false) String ordStatCd) {
        return accountQueryService.orders(principal.mbrNo(), page, size, ordStatCd);
    }
}
