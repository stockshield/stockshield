package com.stockshield.fds.api;

/**
 * 출금 요청. rcvBankNm은 앞뒤 공백을 지운 값, rcvAcntNo는 하이픈을 지운 숫자다(합의서 6절 요청 내용 해시).
 * 출금 비밀번호와 받는 계좌번호는 toString에 나오지 않는다.
 */
public record WithdrawCommand(long wdrAmt, String rcvBankNm, String rcvAcntNo,
                              String idmpKey, String wdrPwd, RequestMeta meta) {

    @Override
    public String toString() {
        return "WithdrawCommand[wdrAmt=" + wdrAmt + ", rcvBankNm=" + rcvBankNm
                + ", idmpKey=" + idmpKey + ", wdrPwd=" + (wdrPwd == null ? "null" : "****") + "]";
    }
}
