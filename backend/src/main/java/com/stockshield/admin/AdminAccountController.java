package com.stockshield.admin;

import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.stockshield.auth.AuthPrincipal;

/** 관리자 계좌 잠금·해제 API (API 명세서 'API_명세서' 27행). ROLE_ADMIN만 들어옴(SecurityConfig) */
@RestController
public class AdminAccountController {

    public record StatusRequest(String acntStatCd, String actRsnCont) {
    }

    private final AdminAccountService accountService;

    public AdminAccountController(AdminAccountService accountService) {
        this.accountService = accountService;
    }

    @PutMapping("/api/v1/admin/accounts/{acntNo}/status")
    public Map<String, Object> changeStatus(@AuthenticationPrincipal AuthPrincipal principal,
                                            @PathVariable("acntNo") String acntNo, @RequestBody StatusRequest body) {
        return accountService.changeStatus(principal.mbrNo(), acntNo, body.acntStatCd(), body.actRsnCont());
    }
}
