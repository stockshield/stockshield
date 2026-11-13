package com.stockshield.admin;

import com.stockshield.common.error.ApiException;
import com.stockshield.common.error.ErrorCode;

/** 관리자 조치 사유(actRsnCont): 앞뒤 공백을 지운 1~500자, 필수 (API 명세서 'API_명세서' 25·27행, FDS_ALT_001 #4) */
final class ActionReasons {

    static final int MAX_LENGTH = 500;

    private ActionReasons() {
    }

    static String normalize(String actRsnCont) {
        if (actRsnCont == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        String reason = actRsnCont.strip();
        // act_rsn_cont VARCHAR(500)은 글자 수 기준이라 code point로 셈
        if (reason.isEmpty() || reason.codePointCount(0, reason.length()) > MAX_LENGTH) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        return reason;
    }
}
