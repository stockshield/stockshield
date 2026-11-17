package com.stockshield.common.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.stockshield.support.IntegrationTestSupport;
import com.stockshield.support.SqlStates;

/**
 * SYS_ENV_001 #10: 스키마는 Flyway SQL로 만들고 Hibernate는 validate만 한다.
 * 이 테스트가 뜬다는 것 자체가 "migrate 성공 + 엔티티와 스키마 일치(validate)"의 증거다.
 */
class SchemaMigrationTest extends IntegrationTestSupport {

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Flyway 마이그레이션이 모두 적용된다")
    void allMigrationsApplied() {
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().applied()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("테이블 정의서의 16개 테이블과 일봉 price_daily(V6)가 모두 만들어진다")
    void allTablesCreated() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'",
                String.class);

        assertThat(tables).containsExactlyInAnyOrder(
                "member", "account", "login_history", "trusted_access",
                "stock_master", "price_minute", "news", "news_stock",
                "orders", "trade_history", "holding", "withdrawal",
                "fds_detection", "fds_action_log", "reconcile_run", "reconcile_result",
                "price_daily");
    }

    @Test
    @DisplayName("DB 세션 시간대는 Asia/Seoul이다")
    void sessionTimeZoneIsSeoul() {
        String timeZone = jdbcTemplate.queryForObject("SHOW TIME ZONE", String.class);
        assertThat(timeZone).isEqualTo("Asia/Seoul");
    }

    @Test
    @DisplayName("news.news_url은 VARCHAR(1000) NOT NULL이다 (V5, 대기 목록 #11)")
    void newsUrlColumn() {
        Map<String, Object> col = jdbcTemplate.queryForMap(
                "SELECT data_type, character_maximum_length, is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'news' AND column_name = 'news_url'");

        assertThat(col)
                .containsEntry("data_type", "character varying")
                .containsEntry("character_maximum_length", 1000)
                .containsEntry("is_nullable", "NO");
    }

    @Test
    @DisplayName("원문 주소 없이 뉴스를 넣으면 거부된다 (NEWS_AI_001 #4)")
    void newsWithoutUrlRejected() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO news (news_id, news_titl, news_cont, pub_ts) VALUES (?, ?, ?, ?)",
                "TEST-NO-URL", "제목", "요약", java.sql.Timestamp.valueOf("2026-11-16 09:00:00")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("price_daily 컬럼은 price_minute과 같은 타입이고 모두 NOT NULL이다 (V6, 대기 목록 #90)")
    void priceDailyColumns() {
        List<Map<String, Object>> cols = jdbcTemplate.queryForList(
                "SELECT column_name, data_type, character_maximum_length, numeric_precision, numeric_scale, is_nullable "
                        + "FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'price_daily' "
                        + "ORDER BY ordinal_position");

        List<String> described = cols.stream()
                .map(c -> c.get("column_name") + " " + c.get("data_type") + " "
                        + c.get("character_maximum_length") + " " + c.get("numeric_precision") + " "
                        + c.get("numeric_scale") + " " + c.get("is_nullable"))
                .toList();

        assertThat(described).containsExactly(
                "tck_cd character varying 10 null null NO",
                "bs_dt character varying 8 null null NO",
                "open_prc numeric null 15 0 NO",
                "high_prc numeric null 15 0 NO",
                "low_prc numeric null 15 0 NO",
                "close_prc numeric null 15 0 NO",
                "trd_vol bigint null 64 0 NO",
                "reg_ts timestamp without time zone null null null NO");
    }

    @Test
    @DisplayName("price_daily는 같은 종목·같은 날짜 중복, 없는 종목, 음수 거래량을 거부한다 (V6)")
    void priceDailyConstraints() {
        String insert = "INSERT INTO price_daily (tck_cd, bs_dt, open_prc, high_prc, low_prc, close_prc, trd_vol) "
                + "VALUES (?, ?, 100, 110, 90, 105, ?)";
        jdbcTemplate.update(insert, "005930", "19000102", 10L);

        assertThatThrownBy(() -> jdbcTemplate.update(insert, "005930", "19000102", 10L))
                .satisfies(e -> assertThat(SqlStates.of(e)).isEqualTo(SqlStates.UNIQUE_VIOLATION));
        assertThatThrownBy(() -> jdbcTemplate.update(insert, "999999", "19000102", 10L))
                .satisfies(e -> assertThat(SqlStates.of(e)).isEqualTo(SqlStates.FOREIGN_KEY_VIOLATION));
        assertThatThrownBy(() -> jdbcTemplate.update(insert, "005930", "19000103", -1L))
                .satisfies(e -> assertThat(SqlStates.of(e)).isEqualTo(SqlStates.CHECK_VIOLATION));
    }
}
