package com.stockshield.controller;

import com.stockshield.dto.request.LoginRequest;
import com.stockshield.dto.request.RefreshTokenRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /** 로그인 (JWT 발급 + login_history 적재) - 인가: None / 200, 401, 403(5회 실패 30분 잠금) */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        // return ResponseEntity.ok({ accessToken, refreshToken, roleCd })
        return null;
    }

    /** 토큰 재발급 - 인가: None / 200, 401(RT 만료·조작) */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody RefreshTokenRequest request) {
        // return ResponseEntity.ok({ accessToken })
        return null;
    }

    /** 로그아웃 (Redis RT 파기) - 인가: Bearer (USER) / 200 OK */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestBody RefreshTokenRequest request) {
        // return ResponseEntity.ok({})
        return null;
    }
}

