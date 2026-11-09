package com.stockshield.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/reconcile")
public class ReconcileController {

    /** 수동 정합성 점검 트리거 (reconcileDt: YYYYMMDD) - 인가: Bearer (ADMIN) / 200 OK */
    @PostMapping
    public ResponseEntity<?> triggerReconcile() {
        // return ResponseEntity.ok({ diffCount, reconcileDt })
        return null;
    }
}

