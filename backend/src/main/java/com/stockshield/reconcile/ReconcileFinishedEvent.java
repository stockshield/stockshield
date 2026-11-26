package com.stockshield.reconcile;

/** 불일치가 있거나 실패한 대사 실행. 결과가 커밋된 뒤 발행함 */
public record ReconcileFinishedEvent(ReconcileRun run) {
}
