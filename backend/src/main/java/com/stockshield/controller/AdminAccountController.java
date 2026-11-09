package com.stockshield.controller;

import com.stockshield.dto.request.AccountStatusRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/accounts")
public class AdminAccountController {

    /** 계좌 잠금 강제 해제 (fds_action_log 감사 이력 필수) - 인가: Bearer (ADMIN) / 200 OK */
    @PutMapping("/{acntNo}/status")
    public ResponseEntity<?> updateAccountStatus(@PathVariable("acntNo") String acntNo,
                                                 @RequestBody AccountStatusRequest request) {
        // return ResponseEntity.ok({ success })
        return null;
    }
}

