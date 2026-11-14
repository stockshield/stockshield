package com.stockshield.reconcile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 내부 API의 X-Internal-Token 확인 (API 명세서 'API_명세서' 31행, 연결 합의서 9절).
 * 설정 값이 비어 있으면 모든 요청을 거절함. 빈 값끼리 일치로 보면 운영 .env에서 빠졌을 때 내부 API가 열림 (문서 수정 대기 목록 #45).
 */
@Component
public class InternalTokenVerifier {

    private final byte[] expected;

    public InternalTokenVerifier(@Value("${stockshield.internal-token:}") String internalToken) {
        this.expected = internalToken == null ? new byte[0] : internalToken.getBytes(StandardCharsets.UTF_8);
    }

    public boolean matches(String presented) {
        if (expected.length == 0 || presented == null) {
            return false;
        }
        // 앞에서부터 비교해 일찍 끝나면 응답 시간으로 토큰을 한 글자씩 알아낼 수 있어 상수 시간 비교
        return MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8));
    }
}
