package com.stockshield.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 회원 (사용자 및 관리자) */
@Getter
@Entity
@Table(name = "member",
        uniqueConstraints = @UniqueConstraint(name = "UK_MEMBER_01", columnNames = "lgn_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Member extends BaseTimeEntity {

    /** 회원고유번호 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mbr_no")
    private Long mbrNo;

    /** 로그인아이디 */
    @Column(name = "lgn_id", nullable = false, length = 50)
    private String lgnId;

    /** 비밀번호 (BCrypt 단방향 암호화) */
    @Column(name = "lgn_pwd", nullable = false, length = 255)
    private String lgnPwd;

    /** 이름 */
    @Column(name = "mbr_nm", nullable = false, length = 50)
    private String mbrNm;

    /** 이메일주소 */
    @Column(name = "eml_addr", nullable = false, length = 100)
    private String emlAddr;

    /** 권한코드 (Domain: ROLE_USER, ROLE_ADMIN) */
    @Builder.Default
    @Column(name = "role_cd", nullable = false, length = 20)
    private String roleCd = "ROLE_USER";
}

