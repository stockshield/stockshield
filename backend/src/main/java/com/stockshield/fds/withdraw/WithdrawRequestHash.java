package com.stockshield.fds.withdraw;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.stockshield.fds.api.WithdrawCommand;

/**
 * 출금 요청 내용 해시: "{wdrAmt}|{rcvBankNm}|{rcvAcntNo}"를 UTF-8로 SHA-256 한 hex (합의 14).
 * 원장이 정규화한 값(받는 은행은 앞뒤 공백 제거, 받는 계좌는 하이픈 없는 숫자)을 WithdrawCommand로 넘기므로 그대로 쓴다.
 * 428 뒤 같은 키로 금액·받는 은행·받는 계좌를 바꿔 보냈는지 비교하는 데 쓴다(FDS_WDR_001 #2, API 명세서 '공통 규칙' 47행).
 * 출금 비밀번호는 해시에 넣지 않는다.
 */
public final class WithdrawRequestHash {

    private WithdrawRequestHash() {
    }

    public static String of(WithdrawCommand cmd) {
        String content = cmd.wdrAmt() + "|" + cmd.rcvBankNm() + "|" + cmd.rcvAcntNo();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e);
        }
    }
}
