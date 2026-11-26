package com.stockshield.common.web;

import java.util.regex.Pattern;

import com.stockshield.common.error.ApiException;
import com.stockshield.common.error.ErrorCode;
import com.stockshield.fds.api.RequestMeta;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 요청의 IP·기기·User-Agent를 정규화해 RequestMeta로 만든다. FDS는 받은 값을 그대로 저장한다.
 * (SYS_SEC_001 #4, USR_AUTH_002 구현방안, 테이블 정의서 '공통 규칙' 18행, API 명세서 '공통 규칙' 3·4행, 연결 합의서 2절 규칙 9)
 *
 * - connIp: 서버가 직접 읽은 접속 주소(request.getRemoteAddr). 화면이 보낸 값이나 X-Forwarded-For는 읽지 않는다.
 *   배포 프록시(nginx) 뒤에서는 그 주소만 신뢰 프록시로 설정해 Tomcat이 remoteAddr를 바꾼다(P11).
 * - dvcId: X-Device-Id 헤더. UUID 형식(8-4-4-4-12 16진수)이 아니면 400. 회원가입·로그인은 필수, 주문·출금은 없으면 null.
 * - userAgt: User-Agent 앞 255자(문자 단위, 서로게이트 쌍을 자르지 않음). 없거나 비어 있으면 '-'.
 */
public final class RequestMetas {

    public static final String DEVICE_HEADER = "X-Device-Id";
    static final int USER_AGENT_MAX = 255;
    static final String NO_USER_AGENT = "-";

    /** UUID.fromString은 "1-1-1-1-1"도 받아 주므로 형식을 직접 확인한다 */
    private static final Pattern UUID_FORMAT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private RequestMetas() {
    }

    /** 회원가입·로그인: X-Device-Id가 없으면 400 */
    public static RequestMeta requireDevice(HttpServletRequest request) {
        String dvcId = request.getHeader(DEVICE_HEADER);
        if (dvcId == null || dvcId.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        return of(request, dvcId);
    }

    /** 주문·출금: X-Device-Id가 없으면 null로 두고, 있는데 형식이 틀리면 400 */
    public static RequestMeta optionalDevice(HttpServletRequest request) {
        String dvcId = request.getHeader(DEVICE_HEADER);
        return of(request, dvcId == null || dvcId.isBlank() ? null : dvcId);
    }

    private static RequestMeta of(HttpServletRequest request, String dvcId) {
        if (dvcId != null && !UUID_FORMAT.matcher(dvcId).matches()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        return new RequestMeta(request.getRemoteAddr(), dvcId, userAgent(request.getHeader("User-Agent")));
    }

    static String userAgent(String header) {
        if (header == null || header.isBlank()) {
            return NO_USER_AGENT;
        }
        if (header.codePointCount(0, header.length()) <= USER_AGENT_MAX) {
            return header;
        }
        return header.substring(0, header.offsetByCodePoints(0, USER_AGENT_MAX));
    }
}
