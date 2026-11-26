package com.stockshield.ledger;

import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 서비스 대상 종목. 목록 밖 종목은 주문할 수 없음 (API 명세서 'API_명세서' 17행 STOCK_NOT_FOUND) */
@Repository
public class StockMasterReader {

    private final JdbcTemplate jdbcTemplate;

    public StockMasterReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 종목코드 → 종목명. 서비스 대상이 30종목이라 통째로 읽음 */
    public Map<String, String> names() {
        Map<String, String> names = new HashMap<>();
        jdbcTemplate.query("SELECT tck_cd, tck_nm FROM stock_master",
                rs -> {
                    names.put(rs.getString("tck_cd"), rs.getString("tck_nm"));
                });
        return names;
    }

    public boolean exists(String tckCd) {
        Boolean found = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM stock_master WHERE tck_cd = ?)", Boolean.class, tckCd);
        return Boolean.TRUE.equals(found);
    }
}
