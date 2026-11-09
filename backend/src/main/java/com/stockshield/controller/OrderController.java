package com.stockshield.controller;

import com.stockshield.dto.request.OrderRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    /** 주식 매수/매도 주문 (멱등키 필수) - 인가: Bearer (USER) / 200, 400, 403, 423(비관적 락 지연) */
    @PostMapping
    public ResponseEntity<?> placeOrder(@RequestBody OrderRequest request) {
        // return ResponseEntity.ok({ ordNo, ordStatCd, ordPrc, trdAmt })
        return null;
    }
}

