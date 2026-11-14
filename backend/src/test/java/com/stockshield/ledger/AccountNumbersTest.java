package com.stockshield.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 계좌번호 '100' + 일련번호 8자리 + Luhn 검증번호 1자리 (USR_AUTH_001 #3, 테이블 정의서 '공통 규칙' 9행, 부록 #20)
 */
class AccountNumbersTest {

    @Test
    @DisplayName("Luhn 검증번호는 표준 예시(7992739871 → 3)와 같다")
    void luhnMatchesKnownVector() {
        assertThat(AccountNumbers.luhnCheckDigit("7992739871")).isEqualTo(3);
    }

    @Test
    @DisplayName("계좌번호는 숫자 12자리, '100'으로 시작하고 일련번호를 8자리로 채운 뒤 검증번호를 붙인다")
    void formatsTwelveDigits() {
        assertThat(AccountNumbers.of(1)).isEqualTo("100000000016");
        assertThat(AccountNumbers.of(12_345_678)).isEqualTo("100123456780");
        assertThat(AccountNumbers.isValid(AccountNumbers.of(99_999_999))).isTrue();
    }

    @Test
    @DisplayName("어느 한 자리를 잘못 쓰면 검증에 걸린다")
    void detectsAnySingleDigitTypo() {
        String acntNo = AccountNumbers.of(4_821);
        assertThat(AccountNumbers.isValid(acntNo)).isTrue();
        for (int i = 0; i < acntNo.length(); i++) {
            for (char d = '0'; d <= '9'; d++) {
                if (d == acntNo.charAt(i)) {
                    continue;
                }
                String typo = acntNo.substring(0, i) + d + acntNo.substring(i + 1);
                assertThat(AccountNumbers.isValid(typo)).as(typo).isFalse();
            }
        }
    }

    @Test
    @DisplayName("12자리 숫자가 아니면 계좌번호가 아니다")
    void rejectsWrongShape() {
        assertThat(AccountNumbers.isValid(null)).isFalse();
        assertThat(AccountNumbers.isValid("10000000001")).isFalse();
        assertThat(AccountNumbers.isValid("100-00000001-6")).isFalse();
    }
}
