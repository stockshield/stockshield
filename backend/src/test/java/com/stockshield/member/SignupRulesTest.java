package com.stockshield.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.stockshield.common.error.ApiException;

/**
 * 가입 입력값 서버 검증 (USR_AUTH_001 #2, SYS_SEC_001 #5, 문서 수정 대기 목록 #16). 규칙에 맞지 않으면 바꾸지 않고 400.
 */
class SignupRulesTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"abcd", "a1b2c3", "user01", "abcdefghijklmnop"})
    @DisplayName("아이디: 영문 소문자로 시작하는 소문자·숫자 4~16자는 통과")
    void validLgnIds(String lgnId) {
        assertThat(SignupRules.lgnId(lgnId)).isEqualTo(lgnId);
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"abc", "abcdefghijklmnopq", "1abc", "Abcd", "abc_d", "abc d", " abcd", "가나다라"})
    @DisplayName("아이디: 3자 이하·17자 이상·숫자로 시작·대문자·기호·공백은 400 (소문자로 바꿔 주지 않음)")
    void invalidLgnIds(String lgnId) {
        assertThatThrownBy(() -> SignupRules.lgnId(lgnId)).isInstanceOf(ApiException.class);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Test123!", "a1!aaaaa", "P@ssw0rd-long-but-ok"})
    @DisplayName("비밀번호: 8자 이상이고 영문·숫자·특수문자를 모두 쓰면 통과")
    void validPasswords(String pwd) {
        assertThat(SignupRules.isValidPassword(pwd)).isTrue();
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"Te12!", "Testtest!", "12345678!", "Test12345", "Test 123!", "테스트test12!"})
    @DisplayName("비밀번호: 8자 미만, 영문·숫자·특수문자 중 하나라도 없음, 공백·한글은 400")
    void invalidPasswords(String pwd) {
        assertThat(SignupRules.isValidPassword(pwd)).isFalse();
    }

    @Test
    @DisplayName("비밀번호: BCrypt가 72바이트 뒤를 버리므로 72자까지만 받는다")
    void passwordMaxLength() {
        String base = "Aa1!";
        assertThat(SignupRules.isValidPassword(base + "x".repeat(68))).isTrue();   // 72자
        assertThat(SignupRules.isValidPassword(base + "x".repeat(69))).isFalse();  // 73자
    }

    @Test
    @DisplayName("이름: 앞뒤 공백을 빼고 1~50자")
    void memberName() {
        assertThat(SignupRules.mbrNm("  홍길동  ")).isEqualTo("홍길동");
        assertThat(SignupRules.mbrNm("가".repeat(50))).hasSize(50);
        assertThatThrownBy(() -> SignupRules.mbrNm("   ")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> SignupRules.mbrNm("가".repeat(51))).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("이메일: 앞뒤 공백을 빼고 100자 이하, local@domain.tld 꼴")
    void email() {
        assertThat(SignupRules.email(" me@example.com ")).isEqualTo("me@example.com");
        assertThatThrownBy(() -> SignupRules.email("me@example")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> SignupRules.email("me example@x.com")).isInstanceOf(ApiException.class);
        String local = "a".repeat(100 - "@example.com".length());
        assertThat(SignupRules.email(local + "@example.com")).hasSize(100);
        assertThatThrownBy(() -> SignupRules.email("a" + local + "@example.com")).isInstanceOf(ApiException.class);
    }
}
