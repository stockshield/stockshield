package com.stockshield.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 로그인 이력 (FDS 접속이상 탐지용) */
@Getter
@Entity
@Table(name = "login_history",
        indexes = @Index(name = "IDX_LOGIN_HIST_01", columnList = "lgn_id, reg_ts"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class LoginHistory extends BaseRegTimeEntity {

    /** 이력일련번호 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "lgn_hist_seq")
    private Long lgnHistSeq;

    /** 로그인아이디 (실패 시 미가입 ID도 적재되므로 FK 미설정) */
    @Column(name = "lgn_id", nullable = false, length = 50)
    private String lgnId;

    /** 접속IP주소 (IPv4/IPv6) */
    @Column(name = "conn_ip", nullable = false, length = 45)
    private String connIp;

    /** 접속기기정보 (User-Agent) */
    @Column(name = "user_agt", nullable = false, length = 255)
    private String userAgt;

    /** 성공여부 (Domain: Y, N) */
    @Column(name = "succ_yn", nullable = false, length = 1, columnDefinition = "CHAR(1)")
    private String succYn;
}

