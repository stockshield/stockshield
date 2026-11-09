package com.stockshield.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FDS 경보 관리 - 인가: Bearer (ADMIN) / 200 OK, 403 Forbidden */
@RestController
@RequestMapping("/api/v1/admin/alerts")
public class FdsAdminController {

    /** 미처리 경보 목록 조회 (STOMP 누락분 DB 동기화) */
    @GetMapping("/pending")
    public ResponseEntity<?> getPendingAlerts() {
        // return ResponseEntity.ok([ { ordNo, acntNo, tckCd, violRuleNm, warnCnt, dtcSrcCd, regTs } ])
        return null;
    }

    /** 경보 상세 분석 조회 (IP 등 개인정보 마스킹 확인) */
    @GetMapping("/{ordNo}")
    public ResponseEntity<?> getAlertDetail(@PathVariable("ordNo") String ordNo) {
        // return ResponseEntity.ok({ ordNo, fdsRsnData, violRuleNm, warnCnt, dtcSrcCd })
        return null;
    }
}

