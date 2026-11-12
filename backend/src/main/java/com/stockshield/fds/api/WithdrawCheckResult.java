package com.stockshield.fds.api;

/**
 * 대량 출금 판단 결과 (FDS_WDR_001 #1~#3, API 명세서 'API_명세서' 21행).
 * reason은 NOT_LARGE·CONTENT_CHANGED면 null, failCntAfter는 MISMATCH·LOCKED일 때만 있다.
 * dtcSeq: 재요청으로 이어 쓸 기존 PENDING의 번호. 새 요청이면 null.
 */
public record WithdrawCheckResult(Decision decision, LargeReason reason, long dailySum, long threshold,
                                  Integer failCntAfter, Long dtcSeq) {

    public enum Decision { NOT_LARGE, PASSWORD_REQUIRED, PASSED, MISMATCH, LOCKED, CONTENT_CHANGED }

    public enum LargeReason { SINGLE, DAILY_SUM }
}
