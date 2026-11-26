package com.stockshield.member;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.stockshield.auth.AuthPrincipal;
import com.stockshield.common.error.ApiException;
import com.stockshield.common.error.ErrorCode;
import com.stockshield.common.web.RequestMetas;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 회원 API (API 명세서 'API_명세서' 2~4행, 문서 수정 대기 목록 #9의 GET /members/me).
 */
@RestController
@RequestMapping("/api/v1/members")
public class MemberController {

    public record EmailCodeRequest(String emlAddr) {
    }

    /** 금액은 받지 않고 패키지 코드만 받는다 (USR_AUTH_001 #3). 비밀번호는 toString에 나오지 않는다 */
    public record SignupRequest(String lgnId, String lgnPwd, String mbrNm, String emlAddr, String authId,
                                String authCode, String wdrPwd, String initPkgCd) {
        @Override
        public String toString() {
            return "SignupRequest[lgnId=" + lgnId + ", initPkgCd=" + initPkgCd + "]";
        }
    }

    private final SignupService signupService;
    private final MemberRepository members;

    public MemberController(SignupService signupService, MemberRepository members) {
        this.signupService = signupService;
        this.members = members;
    }

    /** 2행 {isAvailable} */
    @GetMapping("/check-id")
    public Map<String, Object> checkId(@RequestParam("lgnId") String lgnId) {
        return Map.of("isAvailable", signupService.isAvailable(lgnId));
    }

    /** 3행 {authId, ttlSec} */
    @PostMapping("/email-code")
    public SignupService.EmailCodeIssued emailCode(@RequestBody EmailCodeRequest body) {
        return signupService.issueEmailCode(body.emlAddr());
    }

    /** 4행 201 {mbrNo, acntNo, initDpsAmt, dpsBal}. 가입한 IP·기기(X-Device-Id 필수)가 첫 신뢰 접속이 된다 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MemberRegistrar.Registered signUp(@RequestBody SignupRequest body, HttpServletRequest request) {
        return signupService.signUp(body, RequestMetas.requireDevice(request));
    }

    /** 새로고침 뒤 로그인한 사람의 이름·권한을 다시 읽는다 {lgnId, mbrNm, roleCd} (대기 목록 #9) */
    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal AuthPrincipal principal) {
        Member m = members.findById(principal.mbrNo()).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lgnId", m.getLgnId());
        body.put("mbrNm", m.getMbrNm());
        body.put("roleCd", m.getRoleCd());
        return body;
    }
}
