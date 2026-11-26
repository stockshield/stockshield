package com.stockshield.fds.detection;

import java.time.LocalDateTime;

/**
 * 탐지 기록이 저장되거나 결과가 바뀌었다는 사실. DetectionRecorder만 발행하고(FDS_LOG_001 #3),
 * 커밋 뒤 DetectionAlertListener가 관제 화면으로 보낸다. 결과가 LOCKED면 긴급 경보다.
 */
public record DetectionChangedEvent(long dtcSeq, DetectionRule rule, DetectionResult result,
                                    String acntNo, LocalDateTime regTs) {

    public boolean urgent() {
        return result == DetectionResult.LOCKED;
    }
}
