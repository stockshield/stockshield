package com.stockshield.fds.api;

/**
 * 원장·회원이 정규화한 요청 정보(합의서 2절 규칙 9). FDS는 그대로 저장한다.
 * connIp는 서버가 직접 읽은 IP, userAgt는 User-Agent 앞 255자(없으면 '-'), dvcId는 X-Device-Id(주문·출금에서 없으면 null).
 * (SYS_SEC_001 #4, 테이블 정의서 '공통 규칙' 18행)
 */
public record RequestMeta(String connIp, String dvcId, String userAgt) {
}
