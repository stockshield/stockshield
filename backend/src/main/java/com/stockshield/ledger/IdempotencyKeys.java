package com.stockshield.ledger;

import java.util.Locale;
import java.util.regex.Pattern;

import com.stockshield.common.error.ApiException;
import com.stockshield.common.error.ErrorCode;

/** 멱등키는 UUID v4 형식만 받음 (SYS_SEC_001 #5, API 명세서 '공통 규칙' 57행) */
public final class IdempotencyKeys {

    private static final Pattern UUID_V4 =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    private IdempotencyKeys() {
    }

    /** 대소문자만 다른 같은 UUID가 다른 키로 저장되지 않게 소문자로 맞춤. 형식이 틀리면 400 */
    public static String normalize(String idmpKey) {
        if (idmpKey == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        String key = idmpKey.toLowerCase(Locale.ROOT);
        if (!UUID_V4.matcher(key).matches()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        return key;
    }
}
