package com.stockshield.fds.ai;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 AI 사유 설명 재요청 (API 명세서 'API_명세서' 26행, FDS_AI_001 #4). 권한(ADMIN)은 SecurityConfig가 검사한다.
 * 202 {dtcSeq, aiStatCd: "REQUESTED"}, 404 DETECTION_NOT_FOUND, 409 AI_RETRY_NOT_ALLOWED.
 * 결과는 /topic/admin/detections로 온다.
 */
@RestController
public class AiReasonController {

    private final DetectionAiExplainer explainer;

    public AiReasonController(DetectionAiExplainer explainer) {
        this.explainer = explainer;
    }

    @PostMapping("/api/v1/admin/detections/{dtcSeq}/ai-reason")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> retry(@PathVariable long dtcSeq) {
        explainer.retry(dtcSeq);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dtcSeq", dtcSeq);
        body.put("aiStatCd", "REQUESTED");
        return body;
    }
}
