package com.stockshield.common.web;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 응답의 시각 형식: ISO 8601 KST, 초 단위 (API 명세서 '공통 규칙' 55행) */
public final class KstTime {

    private static final DateTimeFormatter ISO_KST = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'+09:00'");

    private KstTime() {
    }

    public static String format(LocalDateTime seoulTime) {
        return seoulTime == null ? null : seoulTime.format(ISO_KST);
    }
}
