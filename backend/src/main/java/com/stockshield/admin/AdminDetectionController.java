package com.stockshield.admin;

import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stockshield.auth.AuthPrincipal;
import com.stockshield.common.web.PageBody;

/** 관리자 탐지 기록 API (API 명세서 'API_명세서' 23~25행). ROLE_ADMIN만 들어옴(SecurityConfig) */
@RestController
@RequestMapping("/api/v1/admin/detections")
public class AdminDetectionController {

    public record CheckRequest(String actRsnCont) {
    }

    private final AdminDetectionService detectionService;

    public AdminDetectionController(AdminDetectionService detectionService) {
        this.detectionService = detectionService;
    }

    @GetMapping
    public PageBody list(@RequestParam(name = "ruleCd", required = false) String ruleCd,
                         @RequestParam(name = "chkYn", required = false) String chkYn,
                         @RequestParam(name = "page", defaultValue = "0") int page,
                         @RequestParam(name = "size", defaultValue = "20") int size) {
        return detectionService.list(ruleCd, chkYn, page, size);
    }

    @GetMapping("/{dtcSeq}")
    public Map<String, Object> detail(@PathVariable("dtcSeq") long dtcSeq) {
        return detectionService.detail(dtcSeq);
    }

    @PatchMapping("/{dtcSeq}/check")
    public Map<String, Object> check(@AuthenticationPrincipal AuthPrincipal principal,
                                     @PathVariable("dtcSeq") long dtcSeq, @RequestBody CheckRequest body) {
        return detectionService.check(principal.mbrNo(), dtcSeq, body.actRsnCont());
    }
}
