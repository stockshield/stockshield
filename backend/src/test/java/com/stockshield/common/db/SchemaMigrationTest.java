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
    @DisplayName("테이블 정의서의 16개 테이블이 모두 만들어진다")
    void allTablesCreated() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'",
                String.class);

        assertThat(tables).containsExactlyInAnyOrder(
                "member", "account", "login_history", "trusted_access",
                "stock_master", "price_minute", "news", "news_stock",
                "orders", "trade_history", "holding", "withdrawal",
                "fds_detection", "fds_action_log", "reconcile_run", "reconcile_result");
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
}
