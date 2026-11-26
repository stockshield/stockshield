package com.stockshield.ledger;

import java.util.Arrays;
import java.util.Optional;

/**
 * 초기 예수금 패키지. 가입 요청은 코드만 받고 금액은 서버가 정한다 (USR_AUTH_001 #3, 부록 #10).
 * 코드·금액은 테이블 정의서 '코드 정의' 4~6행(init_pkg_cd).
 */
public enum InitPkg {

    PKG_10M(10_000_000L),
    PKG_30M(30_000_000L),
    PKG_50M(50_000_000L);

    private final long amount;

    InitPkg(long amount) {
        this.amount = amount;
    }

    public long amount() {
        return amount;
    }

    /** 정의되지 않은 코드면 empty (valueOf처럼 예외를 던지지 않는다) */
    public static Optional<InitPkg> of(String code) {
        return Arrays.stream(values()).filter(p -> p.name().equals(code)).findFirst();
    }
}
