package com.stockshield.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 관리자 화면의 아이디·IP 가리기 (FDS_ALT_001 #2·#3, SYS_SEC_001 #4) */
class MaskingTest {

    @Test
    @DisplayName("아이디는 앞 2자만 보인다")
    void lgnId() {
        assertThat(Masking.lgnId("trader01")).isEqualTo("tr***");
        assertThat(Masking.lgnId("ab")).isEqualTo("a***");
    }

    @Test
    @DisplayName("IPv4는 마지막 옥텟, IPv6는 마지막 그룹을 가린다")
    void connIp() {
        assertThat(Masking.connIp("203.0.113.45")).isEqualTo("203.0.113.***");
        assertThat(Masking.connIp("2001:db8::1")).isEqualTo("2001:db8::***");
        assertThat(Masking.connIp("::ffff:203.0.113.45")).isEqualTo("::ffff:203.0.113.***");
        assertThat(Masking.connIp("localhost")).isEqualTo("***");
    }
}
