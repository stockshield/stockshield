package com.stockshield.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/news")
public class NewsController {

    /** 종목 관련 뉴스 조회 (최신 5건, pubTs: ISO 8601 KST) - 인가: None / 200 OK */
    @GetMapping("/stocks/{tckCd}")
    public ResponseEntity<?> getNewsByStock(@PathVariable("tckCd") String tckCd) {
        // return ResponseEntity.ok([ { newsId, newsTitl, aiSmryCont, aiSntmScr, pubTs } ])
        return null;
    }
}

