package com.stockshield.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 시세/종목 - 인가: None */
@RestController
@RequestMapping("/api/v1/market")
public class MarketController {

    /** 종목 자동완성 검색 / 200 OK */
    @GetMapping("/stocks/search")
    public ResponseEntity<?> searchStocks(@RequestParam("keyword") String keyword) {
        // return ResponseEntity.ok([ { tckCd, tckNm } ])
        return null;
    }

    /** 메인 대시보드 조회 (Redis 캐시 기반) / 200 OK */
    @GetMapping("/summary")
    public ResponseEntity<?> getMarketSummary() {
        // return ResponseEntity.ok({ kospi, kosdaq, topRanks })
        return null;
    }

    /** 종목 상세 정보 조회 (1분봉 최대 390건) / 200 OK, 404 Not Found */
    @GetMapping("/stocks/{tckCd}")
    public ResponseEntity<?> getStockDetail(@PathVariable("tckCd") String tckCd) {
        // return ResponseEntity.ok({ tckCd, tckNm, currentPrice, chart })
        return null;
    }
}

