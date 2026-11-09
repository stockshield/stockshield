package com.stockshield.controller;

import com.stockshield.dto.request.AiSummaryRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 내부 전용 API (Spring 배치 ↔ FastAPI) - 인가: Internal (IP 화이트리스트) */
@RestController
@RequestMapping("/api/v1/internal/ai")
public class InternalAiController {

    /** (내부) 뉴스 요약 요청 / 200 OK, 500 Server Error */
    @PostMapping("/summary")
    public ResponseEntity<?> requestNewsSummary(@RequestBody AiSummaryRequest request) {
        // return ResponseEntity.ok({ aiSmryCont, aiSntmScr })
        return null;
    }
}

