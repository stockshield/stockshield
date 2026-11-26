package com.stockshield.auth;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stockshield.common.web.RequestMetas;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 로그인·새 접속 인증·재발송·토큰 재발급·로그아웃 (API 명세서 'API_명세서' 5~9행).
 * IP·User-Agent는 서버가 요청에서 직접 읽고 본문으로 받지 않는다. 기기 ID는 X-Device-Id 헤더(로그인 필수).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record LoginRequest(String lgnId, String lgnPwd) {
        @Override
        public String toString() {
            return "LoginRequest[lgnId=" + lgnId + "]";
        }
    }

    public record EmailVerifyRequest(String authId, String authCode) {
    }

    public record EmailResendRequest(String authId) {
    }

    public record RefreshRequest(String refreshToken) {
    }

    private final LoginService loginService;
    private final TokenService tokenService;

    public AuthController(LoginService loginService, TokenService tokenService) {
        this.loginService = loginService;
        this.tokenService = tokenService;
    }

    /** 5행: 아는 IP·기기면 200 토큰, 처음 보는 IP·기기면 202 {authRequired, authId, mskEmlAddr} */
    @PostMapping("/login")
    public ResponseEntity<Object> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        var meta = RequestMetas.requireDevice(request);
        LoginService.LoginResult result = loginService.login(body.lgnId(), body.lgnPwd(), meta);
        if (result instanceof LoginService.LoginResult.AuthRequired required) {
            Map<String, Object> accepted = new LinkedHashMap<>();
            accepted.put("authRequired", true);
            accepted.put("authId", required.authId());
            accepted.put("mskEmlAddr", required.mskEmlAddr());
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
        }
        return ResponseEntity.ok(((LoginService.LoginResult.Authenticated) result).tokens());
    }

    /** 6행 */
    @PostMapping("/email-verify")
    public TokenService.Tokens emailVerify(@RequestBody EmailVerifyRequest body) {
        return loginService.verifyNewAccess(body.authId(), body.authCode());
    }

    /** 7행: {ttlSec} 남은 초 */
    @PostMapping("/email-resend")
    public Map<String, Object> emailResend(@RequestBody EmailResendRequest body) {
        return Map.of("ttlSec", loginService.resend(body.authId()));
    }

    /** 8행: {accessToken} */
    @PostMapping("/refresh")
    public Map<String, Object> refresh(@RequestBody RefreshRequest body) {
        return Map.of("accessToken", tokenService.refresh(body.refreshToken()));
    }

    /** 9행: Refresh Token을 Redis에서 지운다. 본문이 없거나 refreshToken이 null이어도 200 {} */
    @PostMapping("/logout")
    public Map<String, Object> logout(@AuthenticationPrincipal AuthPrincipal principal,
                                      @RequestBody(required = false) RefreshRequest body) {
        tokenService.logout(principal.mbrNo(), body == null ? null : body.refreshToken());
        return Map.of();
    }
}
