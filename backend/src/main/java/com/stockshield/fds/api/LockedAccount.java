package com.stockshield.fds.api;

/**
 * 원장이 FOR NO KEY UPDATE로 읽은 값. FDS는 account를 다시 읽지 않는다 (FDS_WDR_001 구현방안).
 * 출금 비밀번호 해시는 toString에 나오지 않는다.
 */
public record LockedAccount(String acntNo, long mbrNo, String wdrPwdHash, int wdrPwdFailCnt) {

    @Override
    public String toString() {
        return "LockedAccount[acntNo=" + acntNo + ", mbrNo=" + mbrNo
                + ", wdrPwdFailCnt=" + wdrPwdFailCnt + "]";
    }
}
