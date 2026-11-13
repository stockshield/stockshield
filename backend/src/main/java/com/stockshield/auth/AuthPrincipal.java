package com.stockshield.auth;

/** Access Token에서 꺼낸 로그인한 사람. 컨트롤러는 @AuthenticationPrincipal로 받는다 */
public record AuthPrincipal(long mbrNo, String roleCd) {
}
