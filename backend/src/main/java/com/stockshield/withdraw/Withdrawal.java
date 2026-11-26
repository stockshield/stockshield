package com.stockshield.withdraw;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import com.stockshield.common.entity.BaseRegTimeEntity;
import com.stockshield.fds.api.WithdrawCommand;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 모의 출금 - INSERT 전용. 완료·거절 결과를 한 번 저장하고 고치지 않음 (TRD_WDR_001 #4, TRD_LED_001 #5).
 * 처리 후 출금 비밀번호 오류 횟수까지 남겨, 같은 키 재요청의 남은 횟수가 처음 응답과 같게 함 (요구사항명세서 부록 #16).
 */
@Getter
@Entity
@Immutable
@Table(name = "withdrawal")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Withdrawal extends BaseRegTimeEntity implements Persistable<String> {

    public static final String DONE = "DONE";
    public static final String REJECTED = "REJECTED";

    @Id
    @Column(name = "wdr_no", length = 30, nullable = false, updatable = false)
    private String wdrNo;

    @Column(name = "acnt_no", length = 20, nullable = false, updatable = false)
    private String acntNo;

    @Column(name = "wdr_amt", columnDefinition = "numeric(19,0)", nullable = false, updatable = false)
    private Long wdrAmt;

    @Column(name = "rcv_bank_nm", length = 50, nullable = false, updatable = false)
    private String rcvBankNm;

    @Column(name = "rcv_acnt_no", length = 30, nullable = false, updatable = false)
    private String rcvAcntNo;

    /** 출금 비밀번호를 확인했으면 Y. Y일 때만 postPwdFailCnt가 있음(CHECK) */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "add_auth_yn", length = 1, nullable = false, updatable = false)
    private String addAuthYn;

    @Column(name = "post_pwd_fail_cnt", updatable = false)
    private Integer postPwdFailCnt;

    @Column(name = "wdr_stat_cd", length = 10, nullable = false, updatable = false)
    private String wdrStatCd;

    @Column(name = "rjct_rsn_cd", length = 30, updatable = false)
    private String rjctRsnCd;

    /** 완료일 때만 있음(CHECK) */
    @Column(name = "post_dps_bal", columnDefinition = "numeric(19,0)", updatable = false)
    private Long postDpsBal;

    @Column(name = "idmp_key", length = 36, nullable = false, updatable = false)
    private String idmpKey;

    @Column(name = "conn_ip", length = 45, nullable = false, updatable = false)
    private String connIp;

    @Column(name = "dvc_id", length = 64, updatable = false)
    private String dvcId;

    @Column(name = "user_agt", length = 255, nullable = false, updatable = false)
    private String userAgt;

    @Column(name = "trc_id", length = 36, nullable = false, updatable = false)
    private String trcId;

    @Transient
    private boolean isNew = true;

    /**
     * @param rjctRsnCd      null이면 완료
     * @param postPwdFailCnt 출금 비밀번호를 확인했을 때만(아니면 null)
     * @param postDpsBal     완료일 때만(거절이면 null)
     */
    static Withdrawal of(String wdrNo, String acntNo, WithdrawCommand cmd, String rjctRsnCd,
                         Integer postPwdFailCnt, Long postDpsBal, String trcId) {
        Withdrawal w = new Withdrawal();
        w.wdrNo = wdrNo;
        w.acntNo = acntNo;
        w.wdrAmt = cmd.wdrAmt();
        w.rcvBankNm = cmd.rcvBankNm();
        w.rcvAcntNo = cmd.rcvAcntNo();
        w.addAuthYn = postPwdFailCnt == null ? "N" : "Y";
        w.postPwdFailCnt = postPwdFailCnt;
        w.wdrStatCd = rjctRsnCd == null ? DONE : REJECTED;
        w.rjctRsnCd = rjctRsnCd;
        w.postDpsBal = postDpsBal;
        w.idmpKey = cmd.idmpKey();
        w.connIp = cmd.meta().connIp();
        w.dvcId = cmd.meta().dvcId();
        w.userAgt = cmd.meta().userAgt();
        w.trcId = trcId;
        return w;
    }

    public boolean isDone() {
        return DONE.equals(wdrStatCd);
    }

    /** 같은 키에 다른 내용인지 볼 때 비교하는 값: 금액·받는 은행·받는 계좌. 출금 비밀번호는 비교하지 않음 (TRD_WDR_001 #3) */
    boolean sameContent(WithdrawCommand cmd) {
        return wdrAmt == cmd.wdrAmt() && rcvBankNm.equals(cmd.rcvBankNm()) && rcvAcntNo.equals(cmd.rcvAcntNo());
    }

    @Override
    public String getId() {
        return wdrNo;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
