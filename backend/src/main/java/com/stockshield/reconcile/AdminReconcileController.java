package com.stockshield.reconcile;

import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stockshield.auth.AuthPrincipal;
import com.stockshield.common.web.PageBody;

/** 원장 대사 관리자 API (API 명세서 'API_명세서' 28~30행). ROLE_ADMIN만 들어옴(SecurityConfig) */
@RestController
@RequestMapping("/api/v1/admin/reconcile")
public class AdminReconcileController {

    private final ReconcileService reconcileService;

    public AdminReconcileController(ReconcileService reconcileService) {
        this.reconcileService = reconcileService;
    }

    @PostMapping
    public Map<String, Object> run(@AuthenticationPrincipal AuthPrincipal principal) {
        return reconcileService.runManual(principal.mbrNo());
    }

    @GetMapping
    public PageBody history(@RequestParam(name = "page", defaultValue = "0") int page,
                            @RequestParam(name = "size", defaultValue = "20") int size) {
        return reconcileService.history(page, size);
    }

    @GetMapping("/{rcclRunNo}")
    public Map<String, Object> detail(@PathVariable("rcclRunNo") long rcclRunNo,
                                      @RequestParam(name = "diffOnly", defaultValue = "true") boolean diffOnly) {
        return reconcileService.detail(rcclRunNo, diffOnly);
    }
}
