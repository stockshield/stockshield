package com.stockshield.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 서비스 대상 종목 확인. 목록 밖 종목은 주문할 수 없음 (API 명세서 'API_명세서' 17행 STOCK_NOT_FOUND) */
@Repository
public class StockMasterReader {

    private final JdbcTemplate jdbcTemplate;

    public StockMasterReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean exists(String tckCd) {
        Boolean found = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM stock_master WHERE tck_cd = ?)", Boolean.class, tckCd);
        return Boolean.TRUE.equals(found);
    }
}
