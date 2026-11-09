package com.stockshield.controller;

import com.stockshield.dto.request.SignupRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/members")
public class MemberController {

    /** 아이디 중복 확인 - 인가: None / 200 OK */
    @GetMapping("/check-id")
    public ResponseEntity<?> checkId(@RequestParam("lgnId") String lgnId) {
        // return ResponseEntity.ok(Map.of("isAvailable", Boolean))
        return null;
    }

    /** 회원가입 및 계좌 개설 (단일 트랜잭션) - 인가: None / 201 Created, 400 Bad Request */
    @PostMapping
    public ResponseEntity<?> signup(@RequestBody SignupRequest request) {
        // return ResponseEntity.status(HttpStatus.CREATED).body({ mbrNo, acntNo, dpsBal })
        return null;
    }
}

