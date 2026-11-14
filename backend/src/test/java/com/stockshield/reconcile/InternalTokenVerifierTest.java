package com.stockshield.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 내부 API 토큰 비교 (API 명세서 'API_명세서' 31행, 문서 수정 대기 목록 #45) */
class InternalTokenVerifierTest {

    @Test
    @DisplayName("설정 토큰이 비어 있으면 빈 값을 포함해 어떤 토큰도 통과하지 않는다")
    void emptyConfiguredTokenRejectsEverything() {
        InternalTokenVerifier verifier = new InternalTokenVerifier("");
        assertThat(verifier.matches("")).isFalse();
        assertThat(verifier.matches(null)).isFalse();
        assertThat(verifier.matches("anything")).isFalse();
    }

    @Test
    @DisplayName("설정 토큰과 정확히 같을 때만 통과한다")
    void exactMatchOnly() {
        InternalTokenVerifier verifier = new InternalTokenVerifier("secret-token");
        assertThat(verifier.matches("secret-token")).isTrue();
        assertThat(verifier.matches("secret-tokem")).isFalse();
        assertThat(verifier.matches("secret-token ")).isFalse();
        assertThat(verifier.matches(null)).isFalse();
    }
}
