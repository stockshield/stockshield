package com.stockshield.member;

import com.stockshield.common.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원(사용자·관리자). 테이블 정의서 '테이블정의서' 1~11행(member).
 * 이메일은 새 접속 인증 코드를 받는 곳이라 바꾸지 않는다(USR_AUTH_001 #5, 변경 기능 없음).
 * 비밀번호는 BCrypt 해시만 들고, toString을 만들지 않는다(로그에 해시를 남기지 않음, 연결 합의서 2절 규칙 8).
 */
@Getter
@Entity
@Table(name = "member")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseTimeEntity {

    public static final String ROLE_USER = "ROLE_USER";
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mbr_no")
    private Long mbrNo;

    @Column(name = "lgn_id", length = 50, nullable = false, updatable = false)
    private String lgnId;

    @Column(name = "lgn_pwd", length = 255, nullable = false)
    private String lgnPwd;

    @Column(name = "mbr_nm", length = 50, nullable = false)
    private String mbrNm;

    @Column(name = "eml_addr", length = 100, nullable = false, updatable = false)
    private String emlAddr;

    @Column(name = "role_cd", length = 20, nullable = false, updatable = false)
    private String roleCd;

    /** 가입으로 만드는 회원은 언제나 일반 사용자다. 관리자는 Flyway V4로만 만든다(USR_AUTH_003 #3) */
    public static Member user(String lgnId, String lgnPwdHash, String mbrNm, String emlAddr) {
        Member m = new Member();
        m.lgnId = lgnId;
        m.lgnPwd = lgnPwdHash;
        m.mbrNm = mbrNm;
        m.emlAddr = emlAddr;
        m.roleCd = ROLE_USER;
        return m;
    }
}
