package com.stockshield.support;

import java.sql.SQLException;

/** 예외 안쪽의 PostgreSQL SQLSTATE를 꺼낸다. */
public final class SqlStates {

    /** 권한 없음 (permission denied, must be owner) */
    public static final String INSUFFICIENT_PRIVILEGE = "42501";
    /** CHECK 제약 위반 */
    public static final String CHECK_VIOLATION = "23514";

    private SqlStates() {
    }

    public static String of(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null) {
            if (cause instanceof SQLException sqlException && sqlException.getSQLState() != null) {
                return sqlException.getSQLState();
            }
            cause = cause.getCause();
        }
        return null;
    }
}
