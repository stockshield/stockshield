package com.stockshield.fds.detection;

import java.time.LocalDateTime;

/**
 * 행 잠금(FOR NO KEY UPDATE)으로 읽은 인증 대기(PENDING) 탐지 기록.
 * reqHash는 대량 출금 428 때 dtl_data에 남긴 요청 내용 해시다(로그인 탐지는 null).
 */
public record PendingDetection(long dtcSeq, LocalDateTime regTs, String reqHash) {
}
