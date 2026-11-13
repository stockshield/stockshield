package com.stockshield.admin;

/** 관리자 화면에 보이는 개인정보 가리기 (FDS_ALT_001 #2·#3, SYS_SEC_001 #4) */
final class Masking {

    private Masking() {
    }

    /** 앞 2자만 보임. 새 접속 인증의 가린 이메일(mskEmlAddr)과 같은 규칙 (예: te***) */
    static String lgnId(String lgnId) {
        if (lgnId == null || lgnId.isEmpty()) {
            return lgnId;
        }
        int keep = lgnId.length() <= 2 ? 1 : 2;
        return lgnId.substring(0, keep) + "***";
    }

    /** IPv4는 마지막 옥텟, IPv6는 마지막 그룹을 가림 (예: 203.0.113.***, 2001:db8::***) */
    static String connIp(String connIp) {
        if (connIp == null || connIp.isEmpty()) {
            return connIp;
        }
        int dot = connIp.lastIndexOf('.');
        int colon = connIp.lastIndexOf(':');
        int cut = Math.max(dot, colon);
        return cut < 0 ? "***" : connIp.substring(0, cut + 1) + "***";
    }
}
