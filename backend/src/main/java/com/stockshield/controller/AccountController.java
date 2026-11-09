package com.stockshield.controller;

import com.stockshield.dto.request.SafeModeRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 내 계좌 - 인가: Bearer (USER) / 대상 계좌는 토큰의 회원 기준으로 식별 */
@RestController
@RequestMapping("/api/v1/accounts/me")
public class AccountController {

    /** 내 계좌 요약 조회 / 200 OK */
    @GetMapping("/summary")
    public ResponseEntity<?> getAccountSummary() {
        // return ResponseEntity.ok({ acntNo, dpsBal, totalEvalAmt, totalPnl, yieldRt, safeModeYn })
        return null;
    }

    /** 보유 종목 리스트 조회 / 200 OK */
    @GetMapping("/holdings")
    public ResponseEntity<?> getHoldings() {
        // return ResponseEntity.ok([ { tckCd, tckNm, hldQty, avgBuyPrc, evalPnl } ])
        return null;
    }

    /** 주문 및 체결 내역 조회 (최신순 페이징: page, size) / 200 OK */
    @GetMapping("/orders")
    public ResponseEntity<?> getOrders(Pageable pageable) {
        // return ResponseEntity.ok({ content: [ { ordNo, tckCd, ordStatCd } ], totalElements })
        return null;
    }

    /** 안심모드 설정(ON/OFF) / 200 OK */
    @PatchMapping("/safe-mode")
    public ResponseEntity<?> updateSafeMode(@RequestBody SafeModeRequest request) {
        // return ResponseEntity.ok({ success })
        return null;
    }
}

