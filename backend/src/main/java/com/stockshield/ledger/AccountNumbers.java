package com.stockshield.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 계좌번호 채번: '100' + acnt_no_seq 8자리 + Luhn 검증번호 1자리 = 숫자 12자리.
 * (USR_AUTH_001 #3, 테이블 정의서 '공통 규칙' 9행, '테이블정의서' 16행(account.acnt_no), 부록 #20)
 * 검증번호를 붙여 한 자리 오타가 다른 계좌를 가리키지 않게 한다.
 */
@Component
public class AccountNumbers {

    static final String PREFIX = "100";

    private final JdbcTemplate jdbcTemplate;

    public AccountNumbers(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 시퀀스에서 다음 번호를 받는다. 시퀀스 값은 롤백돼도 되돌아가지 않아 번호가 비어도 겹치지는 않는다 */
    public String next() {
        Long seq = jdbcTemplate.queryForObject("SELECT nextval('acnt_no_seq')", Long.class);
        if (seq == null) {
            throw new IllegalStateException("계좌번호 시퀀스 값을 받지 못했습니다");
        }
        return of(seq);
    }

    static String of(long seq) {
        String body = PREFIX + String.format("%08d", seq);
        return body + luhnCheckDigit(body);
    }

    /** Luhn 검증번호: 오른쪽(검증번호 자리 바로 왼쪽)부터 한 자리 건너 두 배, 9를 넘으면 9를 뺀 합을 10의 배수로 맞춘다 */
    static int luhnCheckDigit(String digits) {
        int sum = 0;
        boolean doubleIt = true;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return (10 - sum % 10) % 10;
    }

    /** 검증번호까지 포함한 번호가 Luhn 검사를 통과하는지 */
    public static boolean isValid(String acntNo) {
        if (acntNo == null || !acntNo.matches("\\d{12}")) {
            return false;
        }
        return luhnCheckDigit(acntNo.substring(0, 11)) == acntNo.charAt(11) - '0';
    }
}
