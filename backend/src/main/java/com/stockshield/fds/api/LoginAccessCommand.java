package com.stockshield.fds.api;

/** 비밀번호가 맞은 뒤 새 접속 판단을 요청한다 (FDS_LGN_001 #1) */
public record LoginAccessCommand(long mbrNo, RequestMeta meta) {
}
