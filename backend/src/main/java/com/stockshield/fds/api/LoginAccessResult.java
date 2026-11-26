package com.stockshield.fds.api;

import java.time.LocalDateTime;

/** NEW_ACCESS일 때만 dtcSeq·expiresAt(탐지 기록 reg_ts + 5분)이 있다 */
public record LoginAccessResult(Type type, Long dtcSeq, LocalDateTime expiresAt) {

    public enum Type { KNOWN, NEW_ACCESS }
}
