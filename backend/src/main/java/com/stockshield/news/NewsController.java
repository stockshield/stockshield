package com.stockshield.news;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.stockshield.common.error.ApiException;
import com.stockshield.common.error.ErrorCode;

/**
 * 종목 관련 뉴스 조회 (API 명세서 14행, NEWS_AI_001 "뉴스 출력"). 로그인 없이 볼 수 있다(SecurityConfig).
 *
 * GET /api/v1/news/stocks/{tckCd} → 그 종목에 연결된 최신 기사 5건(발행 시각 최신순)
 * [{newsId, newsTitl, newsUrl, aiSmryCont, aiSntmScr, pubTs}]. 요약 전 기사는 aiSmryCont·aiSntmScr가 null이다.
 * 없는 종목 코드는 404 STOCK_NOT_FOUND (종목 상세와 같음).
 */
@RestController
public class NewsController {

    static final int LIMIT = 5;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'+09:00'");

    private final JdbcTemplate jdbc;

    public NewsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/news/stocks/{tckCd}")
    public List<Map<String, Object>> stockNews(@PathVariable String tckCd) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM stock_master WHERE tck_cd = ?", Integer.class, tckCd);
        if (exists == null || exists == 0) {
            throw new ApiException(ErrorCode.STOCK_NOT_FOUND, Map.of("tckCd", tckCd));
        }
        return jdbc.query("""
                SELECT n.news_id, n.news_titl, n.news_url, n.ai_smry_cont, n.ai_sntm_scr, n.pub_ts
                FROM news n JOIN news_stock s ON s.news_id = n.news_id
                WHERE s.tck_cd = ?
                ORDER BY n.pub_ts DESC, n.news_id
                LIMIT ?
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("newsId", rs.getString("news_id"));
            m.put("newsTitl", rs.getString("news_titl"));
            m.put("newsUrl", rs.getString("news_url"));
            m.put("aiSmryCont", rs.getString("ai_smry_cont"));
            m.put("aiSntmScr", rs.getBigDecimal("ai_sntm_scr"));
            m.put("pubTs", rs.getObject("pub_ts", LocalDateTime.class).format(ISO));
            return m;
        }, tckCd, LIMIT);
    }
}
