package com.stockshield.ledger;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 주문번호 채번: 'O' + YYYYMMDD + ord_no_seq 9자리 (테이블 정의서 '공통 규칙' 9행) */
@Component
public class LedgerNumbers {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public LedgerNumbers(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /** 시퀀스 값은 롤백돼도 되돌아가지 않아, 번호가 비어도 겹치지는 않음 */
    public String nextOrdNo() {
        return "O" + today() + String.format("%09d", next("ord_no_seq"));
    }

    private String today() {
        return LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private long next(String sequence) {
        Long seq = jdbcTemplate.queryForObject("SELECT nextval('" + sequence + "')", Long.class);
        if (seq == null) {
            throw new IllegalStateException("시퀀스 값을 받지 못했습니다 " + sequence);
        }
        return seq;
    }
}
