package com.stockshield.fds.api;

import java.time.LocalDateTime;

/** 체결 주문. 요청 IP·기기·User-Agent는 orders에 저장된 값이다 (FDS_TRD_001 #2) */
public record FilledOrderView(String ordNo, long mbrNo, String acntNo, String tckCd, String ordDvCd,
                              String connIp, String dvcId, String userAgt, long trdAmt, LocalDateTime regTs) {
}
