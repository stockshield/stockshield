package com.stockshield.ledger;

import org.springframework.data.domain.Persistable;

import com.stockshield.common.entity.BaseTimeEntity;

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
 * 계좌(모의투자 원장 마스터). 테이블 정의서 '테이블정의서' 13~25행(account).
 *
 * - 가입 때 한 번 만들고(USR_AUTH_001 #3), 이후 예수금·상태·오류 횟수는 계좌 락(SELECT … FOR NO KEY UPDATE) 안에서만 바꾼다(TRD_LED_001 #2).
 * - 초기 패키지·초기 예수금은 대사 기준이라 바꾸지 않는다(updatable = false, ADM_REC_001 #1).
 * - 금액은 NUMERIC(19,0)이고 애플리케이션은 long으로 다룬다(테이블 정의서 '공통 규칙' 3행).
 * - 계좌번호를 직접 정해 넣으므로 Persistable로 새 행임을 알려, save가 SELECT 뒤 merge하지 않고 바로 INSERT하게 한다.
 */
@Getter
@Entity
@Table(name = "account")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account extends BaseTimeEntity implements Persistable<String> {

    public static final String ACTIVE = "ACTIVE";
    public static final String TEMP_LOCKED = "TEMP_LOCKED";
    public static final String LOCKED = "LOCKED";

    @Id
    @Column(name = "acnt_no", length = 20, nullable = false, updatable = false)
    private String acntNo;

    @Column(name = "mbr_no", nullable = false, updatable = false)
    private Long mbrNo;

    @Column(name = "init_pkg_cd", length = 10, nullable = false, updatable = false)
    private String initPkgCd;

    @Column(name = "init_dps_amt", columnDefinition = "numeric(19,0)", nullable = false, updatable = false)
    private Long initDpsAmt;

    @Column(name = "dps_bal", columnDefinition = "numeric(19,0)", nullable = false)
    private Long dpsBal;

    @Column(name = "wdr_pwd", length = 255, nullable = false)
    private String wdrPwd;

    @Column(name = "wdr_pwd_fail_cnt", nullable = false)
    private Integer wdrPwdFailCnt;

    @Column(name = "acnt_stat_cd", length = 12, nullable = false)
    private String acntStatCd;

    @Transient
    private boolean isNew = true;

    /** 개설: 패키지 금액을 초기 예수금과 현재 예수금에 넣고 ACTIVE (USR_AUTH_001 #3) */
    public static Account open(String acntNo, long mbrNo, InitPkg pkg, String wdrPwdHash) {
        Account a = new Account();
        a.acntNo = acntNo;
        a.mbrNo = mbrNo;
        a.initPkgCd = pkg.name();
        a.initDpsAmt = pkg.amount();
        a.dpsBal = pkg.amount();
        a.wdrPwd = wdrPwdHash;
        a.wdrPwdFailCnt = 0;
        a.acntStatCd = ACTIVE;
        return a;
    }

    public boolean isActive() {
        return ACTIVE.equals(acntStatCd);
    }

    // 아래 변경 메서드는 계좌 락을 잡은 트랜잭션 안에서만 부름 (TRD_LED_001 #2)

    public void decreaseDpsBal(long amt) {
        if (amt <= 0 || dpsBal < amt) {
            throw new IllegalStateException("예수금을 차감할 수 없습니다 acntNo=" + acntNo);
        }
        dpsBal -= amt;
    }

    public void increaseDpsBal(long amt) {
        if (amt <= 0) {
            throw new IllegalStateException("예수금을 늘릴 수 없습니다 acntNo=" + acntNo);
        }
        dpsBal = Math.addExact(dpsBal, amt);
    }

    /** 출금 비밀번호가 맞으면 0으로 (FDS_WDR_001 #3) */
    public void resetWdrPwdFailCnt() {
        wdrPwdFailCnt = 0;
    }

    /** 횟수는 FDS가 계산해 준 값을 쓰기만 함(규칙은 FDS 한 곳, 변경은 원장 한 곳. 연결 합의서 6절) */
    public void recordWdrPwdFail(int failCntAfter) {
        wdrPwdFailCnt = failCntAfter;
    }

    /** 5회째 오류: 주문·출금을 모두 막고 관리자만 풂 */
    public void tempLock(int failCntAfter) {
        wdrPwdFailCnt = failCntAfter;
        acntStatCd = TEMP_LOCKED;
    }

    /** 관리자 잠금 (FDS_ALT_001 #4) */
    public void adminLock() {
        acntStatCd = LOCKED;
    }

    /** 관리자 해제: 오류 횟수도 0 (FDS_ALT_001 #4, FDS_WDR_001 #3) */
    public void unlock() {
        acntStatCd = ACTIVE;
        wdrPwdFailCnt = 0;
    }

    @Override
    public String getId() {
        return acntNo;
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
