package com.stockshield.fds.detection;

/** fds_detection.rslt_cd (테이블 정의서 '코드 정의' 30~34행) */
public enum DetectionResult {
    /** 인증 대기. 5분이 지나면 FAIL(만료)로 확정한다 */
    PENDING,
    /** 인증 통과 */
    PASS,
    /** 인증 실패·만료 */
    FAIL,
    /** 5회 오류 잠금 (긴급 경보) */
    LOCKED,
    /** 기록만 (FDS ③) */
    LOGGED
}
