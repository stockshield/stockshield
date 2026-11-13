package com.stockshield.reconcile;

/**
 * 계좌 × 점검 항목 하나의 비교 결과 (reconcile_result 한 행).
 * @param tckCd DEPOSIT이면 null
 */
public record ReconcileItem(String acntNo, String chkItemCd, String tckCd, long expVal, long actlVal, boolean diff) {

    public static final String DEPOSIT = "DEPOSIT";
    public static final String HOLDING = "HOLDING";

    public String diffYn() {
        return diff ? "Y" : "N";
    }
}
