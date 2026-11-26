package com.stockshield.member;

import java.util.regex.Pattern;

import com.stockshield.common.error.ApiException;
import com.stockshield.common.error.ErrorCode;
import com.stockshield.ledger.InitPkg;

/**
 * 가입 입력값 서버 검증. 규칙에 맞지 않으면 바꿔 주지 않고 400 INVALID_REQUEST (SYS_SEC_001 #5, 화면 검증만 믿지 않음).
 *
 * - 아이디: 영문 소문자로 시작하는 영문 소문자·숫자 4~16자 (USR_AUTH_001 #2, 대기 목록 #16)
 * - 비밀번호: 8자 이상, 영문·숫자·특수문자를 모두 포함 (USR_AUTH_001 #2). 최대 72자, 공백 없는 ASCII만 받는다.
 *   BCrypt는 72바이트 뒤를 버려, 더 긴 비밀번호는 앞 72바이트만 같아도 로그인되기 때문이다 (새로 정함)
 * - 이름: 앞뒤 공백을 뺀 1~50자 (대기 목록 #16)
 * - 이메일: 앞뒤 공백을 뺀 100자 이하, 'local@domain.tld' 꼴 (테이블 정의서 '테이블정의서' 8행(member.eml_addr) 100자, 형식은 새로 정함)
 * - 인증 코드·출금 비밀번호: 숫자 6자리 (USR_AUTH_001 #1·#3)
 * - 패키지: PKG_10M·PKG_30M·PKG_50M (테이블 정의서 '코드 정의' 4~6행)
 */
public final class SignupRules {

    static final Pattern LGN_ID = Pattern.compile("^[a-z][a-z0-9]{3,15}$");
    static final int PWD_MIN = 8;
    static final int PWD_MAX = 72;
    static final int MBR_NM_MAX = 50;
    static final int EML_MAX = 100;
    static final int AUTH_ID_MAX = 64;
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern SIX_DIGITS = Pattern.compile("^\\d{6}$");

    /** 검증을 통과한 가입 입력 (이름·이메일은 앞뒤 공백을 뺀 값) */
    public record SignupInput(String lgnId, String lgnPwd, String mbrNm, String emlAddr, String authId,
                              String authCode, String wdrPwd, InitPkg initPkg) {
        @Override
        public String toString() {
            return "SignupInput[lgnId=" + lgnId + ", initPkg=" + initPkg + "]";
        }
    }

    private SignupRules() {
    }

    public static SignupInput validate(MemberController.SignupRequest r) {
        return new SignupInput(
                lgnId(r.lgnId()),
                require(isValidPassword(r.lgnPwd()), r.lgnPwd()),
                mbrNm(r.mbrNm()),
                email(r.emlAddr()),
                require(r.authId() != null && !r.authId().isBlank() && r.authId().length() <= AUTH_ID_MAX, r.authId()),
                require(r.authCode() != null && SIX_DIGITS.matcher(r.authCode()).matches(), r.authCode()),
                require(r.wdrPwd() != null && SIX_DIGITS.matcher(r.wdrPwd()).matches(), r.wdrPwd()),
                InitPkg.of(r.initPkgCd()).orElseThrow(SignupRules::invalid));
    }

    public static String lgnId(String v) {
        return require(v != null && LGN_ID.matcher(v).matches(), v);
    }

    public static String email(String v) {
        String t = v == null ? null : v.strip();
        return require(t != null && t.length() <= EML_MAX && EMAIL.matcher(t).matches(), t);
    }

    static String mbrNm(String v) {
        String t = v == null ? null : v.strip();
        return require(t != null && !t.isEmpty() && t.codePointCount(0, t.length()) <= MBR_NM_MAX, t);
    }

    static boolean isValidPassword(String v) {
        if (v == null || v.length() < PWD_MIN || v.length() > PWD_MAX) {
            return false;
        }
        boolean letter = false;
        boolean digit = false;
        boolean special = false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                return false;
            }
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                letter = true;
            } else if (c >= '0' && c <= '9') {
                digit = true;
            } else {
                special = true;
            }
        }
        return letter && digit && special;
    }

    private static <T> T require(boolean ok, T value) {
        if (!ok) {
            throw invalid();
        }
        return value;
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.INVALID_REQUEST);
    }
}
