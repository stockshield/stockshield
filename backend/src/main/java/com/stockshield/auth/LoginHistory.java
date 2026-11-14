package com.stockshield.auth;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.stockshield.common.entity.BaseRegTimeEntity;
import com.stockshield.fds.api.RequestMeta;

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
 * 로그인 이력 - INSERT 전용. 테이블 정의서 '테이블정의서' 27~37행(login_history), '공통 규칙' 6행.
 * 비밀번호를 확인한 로그인 시도마다 한 행을 남긴다(USR_AUTH_002 #2). 없는 아이디도 남긴다(실패 횟수를 같게 세기 위해).
 * reg_ts는 Clock 기반 JPA Auditing으로 채워 고정 Clock 테스트와 Redis 장애 때의 실패 횟수 계산이 같은 시계를 쓴다.
 */
@Getter
@Entity
@Immutable
@Table(name = "login_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoginHistory extends BaseRegTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "lgn_hist_seq")
    private Long lgnHistSeq;

    @Column(name = "lgn_id", length = 50, nullable = false, updatable = false)
    private String lgnId;

    @Column(name = "conn_ip", length = 45, nullable = false, updatable = false)
    private String connIp;

    @Column(name = "dvc_id", length = 64, nullable = false, updatable = false)
    private String dvcId;

    @Column(name = "user_agt", length = 255, nullable = false, updatable = false)
    private String userAgt;

    /** 비밀번호 일치 여부 Y·N */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "succ_yn", length = 1, nullable = false, updatable = false)
    private String succYn;

    /** 추가 인증(새 접속 이메일 인증) 요구 여부 Y·N */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "add_auth_yn", length = 1, nullable = false, updatable = false)
    private String addAuthYn;

    public static LoginHistory of(String lgnId, RequestMeta meta, boolean passwordMatched, boolean addAuthRequired) {
        LoginHistory h = new LoginHistory();
        h.lgnId = lgnId;
        h.connIp = meta.connIp();
        h.dvcId = meta.dvcId();
        h.userAgt = meta.userAgt();
        h.succYn = passwordMatched ? "Y" : "N";
        h.addAuthYn = addAuthRequired ? "Y" : "N";
        return h;
    }
}
