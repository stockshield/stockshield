package com.stockshield.auth;

/** 인증 코드의 쓰임. 가입 코드로 새 접속 인증을 하거나 그 반대로 쓰지 못하게 코드마다 함께 저장한다 */
public enum AuthPurpose {
    /** 가입 이메일 인증 (USR_AUTH_001 #1). 5회 오류 뒤에는 다시 발송받는다 */
    SIGNUP,
    /** 새 접속 본인 인증 (FDS_LGN_001 #2). 5회 오류·만료 뒤에는 로그인부터 다시 한다 */
    LOGIN
}
