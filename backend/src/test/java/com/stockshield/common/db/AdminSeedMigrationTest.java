package com.stockshield.common.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.stockshield.support.IntegrationTestSupport;

/**
 * 최초 관리자 계정 마이그레이션(V4)을 빈 DB마다 Flyway로 직접 돌려 확인한다.
 * USR_AUTH_003 #3·#4, 문서 수정 대기 목록 #17·#19·#21
 *
 * - 값 없음 + adminRequired=false(로컬·테스트·CI) → 넣지 않고 V4는 적용됨
 * - 값 없음 + adminRequired=true(운영) → 실패하고 V4는 미적용, 값을 넣고 다시 돌리면 적용
 * - 일부만 있음·평문 비밀번호·형식이 틀린 아이디·이메일 → 실패
 * - 정상 → ROLE_ADMIN, 이름 '관리자', 계좌·신뢰 접속 없음, 그 비밀번호가 앱의 PasswordEncoder로 맞는다
 */
class AdminSeedMigrationTest extends IntegrationTestSupport {

    static final String RAW_PASSWORD = "Admin123!";

    @Autowired PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("값이 없고 운영이 아니면 관리자를 넣지 않고 V4는 적용된 것으로 남는다")
    void emptyValuesOutsideProdAreSkipped() {
        String db = newDatabase();
        flyway(db, placeholders("", "", "", "false")).migrate();

        assertThat(currentVersion(db)).isEqualTo("4");
        assertThat(jdbc(db).queryForObject("SELECT COUNT(*) FROM member", Integer.class)).isZero();
    }

    @Test
    @DisplayName("운영(adminRequired=true)에서 값이 없으면 실패해 V4가 미적용으로 남고, 값을 넣고 다시 돌리면 적용된다")
    void emptyValuesInProdFailThenSucceedWithValues() {
        String db = newDatabase();
        assertThatThrownBy(() -> flyway(db, placeholders("", "", "", "true")).migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("운영(prod)");
        assertThat(currentVersion(db)).isEqualTo("3");
        assertThat(flyway(db, placeholders("", "", "", "true")).info().pending()).hasSize(1);

        String hash = passwordEncoder.encode(RAW_PASSWORD);
        flyway(db, placeholders("opsadmin", "ops@stockshield.dev", hash, "true")).migrate();
        assertThat(currentVersion(db)).isEqualTo("4");
        assertThat(jdbc(db).queryForObject("SELECT COUNT(*) FROM member WHERE role_cd = 'ROLE_ADMIN'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("일부만 있거나 평문 비밀번호·잘못된 아이디·이메일이면 어디서든 실패한다")
    void invalidValuesFail() {
        String hash = passwordEncoder.encode(RAW_PASSWORD);
        List<Map<String, String>> cases = List.of(
                placeholders("opsadmin", "", "", "false"),
                placeholders("opsadmin", "ops@stockshield.dev", "", "false"),
                placeholders("opsadmin", "ops@stockshield.dev", RAW_PASSWORD, "false"),
                placeholders("opsadmin", "ops@stockshield.dev", hash.substring(0, 59), "false"),
                placeholders("OpsAdmin", "ops@stockshield.dev", hash, "false"),
                placeholders("opsadmin", "ops.stockshield.dev", hash, "false"),
                placeholders("opsadmin", "o".repeat(90) + "@stockshield.dev", hash, "false"));
        for (Map<String, String> p : cases) {
            String db = newDatabase();
            assertThatThrownBy(() -> flyway(db, p).migrate()).as(p.toString()).isInstanceOf(FlywayException.class);
            assertThat(currentVersion(db)).isEqualTo("3");
            assertThat(jdbc(db).queryForObject("SELECT COUNT(*) FROM member", Integer.class)).isZero();
        }
    }

    @Test
    @DisplayName("정상 값이면 ROLE_ADMIN·이름 '관리자'로 넣고 계좌·신뢰 접속은 만들지 않으며, 그 비밀번호가 맞는다")
    void validValuesCreateAdmin() {
        String db = newDatabase();
        String hash = passwordEncoder.encode(RAW_PASSWORD);
        // .env에서 앞뒤 공백이 붙어 와도 지우고 넣는다
        flyway(db, placeholders(" opsadmin ", "ops@stockshield.dev", hash, "true")).migrate();

        Map<String, Object> admin = jdbc(db).queryForMap("SELECT * FROM member WHERE lgn_id = 'opsadmin'");
        assertThat(admin.get("role_cd")).isEqualTo("ROLE_ADMIN");
        assertThat(admin.get("mbr_nm")).isEqualTo("관리자");
        assertThat(admin.get("eml_addr")).isEqualTo("ops@stockshield.dev");
        assertThat(passwordEncoder.matches(RAW_PASSWORD, (String) admin.get("lgn_pwd"))).isTrue();
        assertThat(jdbc(db).queryForObject("SELECT COUNT(*) FROM account", Integer.class)).isZero();
        assertThat(jdbc(db).queryForObject("SELECT COUNT(*) FROM trusted_access", Integer.class)).isZero();
    }

    @Test
    @DisplayName("V3: 서비스 대상 30종목(코스피 20 + 코스닥 10)이 들어간다")
    void stockMasterSeed() {
        String db = newDatabase();
        flyway(db, placeholders("", "", "", "false")).migrate();
        JdbcTemplate j = jdbc(db);
        assertThat(j.queryForObject("SELECT COUNT(*) FROM stock_master WHERE mkt_cd = 'KOSPI'", Integer.class)).isEqualTo(20);
        assertThat(j.queryForObject("SELECT COUNT(*) FROM stock_master WHERE mkt_cd = 'KOSDAQ'", Integer.class)).isEqualTo(10);
        assertThat(j.queryForObject("SELECT tck_nm FROM stock_master WHERE tck_cd = '005930'", String.class)).isEqualTo("삼성전자");
    }

    // ------------------------------------------------------------------ 도우미

    private static Map<String, String> placeholders(String lgnId, String email, String hash, String required) {
        Map<String, String> p = new HashMap<>();
        p.put("appDbUser", APP_DB_USER);
        p.put("appDbPassword", APP_DB_PASSWORD);
        p.put("adminLgnId", lgnId);
        p.put("adminEmlAddr", email);
        p.put("adminPwdHash", hash);
        p.put("adminRequired", required);
        return p;
    }

    private static Flyway flyway(String db, Map<String, String> placeholders) {
        return Flyway.configure()
                .dataSource(url(db), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .placeholders(placeholders)
                .load();
    }

    private static String currentVersion(String db) {
        var current = flyway(db, placeholders("", "", "", "false")).info().current();
        return current == null ? null : current.getVersion().getVersion();
    }

    /** 공용 컨테이너 안에 이 경우만 쓰는 빈 DB를 만든다(소유자 계정) */
    private static String newDatabase() {
        String db = "v4_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + db);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return db;
    }

    private static String url(String db) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + db;
    }

    private static JdbcTemplate jdbc(String db) {
        return new JdbcTemplate(new DriverManagerDataSource(url(db), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }
}
