package com.stockshield.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import com.stockshield.common.error.ApiException;
import com.stockshield.common.error.ErrorCode;
import com.stockshield.fds.api.RequestMeta;

/**
 * 요청 정보 정규화 (SYS_SEC_001 #4, 테이블 정의서 '공통 규칙' 18행, API 명세서 '공통 규칙' 3·4행, 연결 합의서 2절 규칙 9)
 */
class RequestMetasTest {

    static final String DEVICE = "0f8fad5b-d9cb-469f-a165-70867728950e";

    @Test
    @DisplayName("IP는 서버가 직접 읽은 접속 주소이고, 화면이 보낸 X-Forwarded-For는 믿지 않는다")
    void ipComesFromConnection() {
        MockHttpServletRequest request = request(DEVICE);
        request.setRemoteAddr("198.51.100.7");
        request.addHeader("X-Forwarded-For", "203.0.113.99");

        RequestMeta meta = RequestMetas.requireDevice(request);

        assertThat(meta.connIp()).isEqualTo("198.51.100.7");
        assertThat(meta.dvcId()).isEqualTo(DEVICE);
    }

    @Test
    @DisplayName("회원가입·로그인은 X-Device-Id가 없으면 400 INVALID_REQUEST")
    void deviceRequiredForSignupAndLogin() {
        assertThatThrownBy(() -> RequestMetas.requireDevice(request(null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> RequestMetas.requireDevice(request("  ")))
                .isInstanceOf(ApiException.class);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1", "0f8fad5b-d9cb-469f-a165-70867728950", "0f8fad5bd9cb469fa16570867728950e"})
    @DisplayName("X-Device-Id가 UUID 형식이 아니면 400 (UUID.fromString이 받아 주는 짧은 꼴도 거절)")
    void deviceMustBeUuid(String dvcId) {
        assertThatThrownBy(() -> RequestMetas.requireDevice(request(dvcId))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> RequestMetas.optionalDevice(request(dvcId))).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("주문·출금은 X-Device-Id가 없으면 거절하지 않고 null")
    void deviceOptionalForOrdersAndWithdrawals() {
        assertThat(RequestMetas.optionalDevice(request(null)).dvcId()).isNull();
    }

    @Test
    @DisplayName("User-Agent가 없거나 비어 있으면 '-'")
    void missingUserAgentBecomesDash() {
        assertThat(RequestMetas.requireDevice(request(DEVICE)).userAgt()).isEqualTo("-");
        MockHttpServletRequest blank = request(DEVICE);
        blank.addHeader("User-Agent", "   ");
        assertThat(RequestMetas.requireDevice(blank).userAgt()).isEqualTo("-");
    }

    @Test
    @DisplayName("User-Agent는 앞 255자만 남기고, 이모지(서로게이트 쌍)를 반으로 자르지 않는다")
    void userAgentTruncatedTo255Characters() {
        String longUa = "가".repeat(254) + "😀" + "끝";
        String cut = RequestMetas.userAgent(longUa);
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(255);
        assertThat(cut).endsWith("😀");

        String exact = "a".repeat(255);
        assertThat(RequestMetas.userAgent(exact)).isEqualTo(exact);
    }

    private static MockHttpServletRequest request(String dvcId) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr("127.0.0.1");
        if (dvcId != null) {
            r.addHeader(RequestMetas.DEVICE_HEADER, dvcId);
        }
        return r;
    }
}
