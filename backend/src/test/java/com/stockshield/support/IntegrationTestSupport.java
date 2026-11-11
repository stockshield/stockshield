package com.stockshield.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 실제 PostgreSQL 15.19 컨테이너로 검증하는 통합 테스트의 공통 설정.
 * 운영과 같이 마이그레이션은 소유자 계정(컨테이너 기본 계정)으로, 애플리케이션은 앱 계정으로 접속한다.
 * 컨테이너는 테스트 전체에서 한 번만 띄운다.
 *
 * 테스트 메서드에 @Transactional을 붙이지 않는다. 커밋·롤백·락을 실제와 같게 확인하기 위해서다.
 */
@SpringBootTest
public abstract class IntegrationTestSupport {

    protected static final String APP_DB_USER = "stockshield_app";
    protected static final String APP_DB_PASSWORD = "app-test-password";

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:15.19"))
                    .withEnv("TZ", "Asia/Seoul")
                    .withEnv("PGTZ", "Asia/Seoul");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        // 애플리케이션: 앱 계정
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_DB_USER);
        registry.add("spring.datasource.password", () -> APP_DB_PASSWORD);
        // 마이그레이션: 소유자 계정
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.appDbUser", () -> APP_DB_USER);
        registry.add("spring.flyway.placeholders.appDbPassword", () -> APP_DB_PASSWORD);
    }
}
