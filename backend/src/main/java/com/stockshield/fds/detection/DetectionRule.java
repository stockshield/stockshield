package com.stockshield.fds.detection;

/** fds_detection.rule_cd (테이블 정의서 '코드 정의' 27~29행) */
public enum DetectionRule {
    /** FDS ① 새 접속 본인 인증 */
    LOGIN_NEW_ACCESS,
    /** FDS ② 대량 출금 추가 인증 */
    WITHDRAW_LARGE,
    /** FDS ③ 새 IP 직후 거래 */
    TRADE_NEW_IP
}
