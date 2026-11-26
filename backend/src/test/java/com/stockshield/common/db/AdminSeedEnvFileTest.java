package com.stockshield.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

/**
 * 로컬 실행에서 Spring이 infra/.env를 .properties로 읽을 때 BCrypt 해시의 '$'가 그대로 들어오는지 확인한다 (할 일 목록 3-2·3-18).
 * application.yml은 optional:file:../infra/.env[.properties]로 읽고, V4 placeholder는 ${ADMIN_PWD_HASH:}로 받는다.
 * 따옴표로 감싸면 따옴표까지 값이 되어 V4 형식 검사에 걸린다. 그래서 로컬 .env에는 따옴표 없이 쓴다.
 */
class AdminSeedEnvFileTest {

    static final String HASH = "$2a$10$abcdefghijklmnopqrstuuWb0eV8M4L1y7.r9X4iBWm6p0A1yQf3e";

    @TempDir
    Path dir;

    @Test
    @DisplayName("따옴표 없이 쓴 해시는 '$'까지 그대로 읽힌다")
    void unquotedHashIsReadAsIs() throws Exception {
        assertThat(resolve("ADMIN_PWD_HASH=" + HASH)).isEqualTo(HASH);
    }

    @Test
    @DisplayName("따옴표로 감싸면 따옴표가 값에 남는다(로컬 .env에는 따옴표를 쓰지 않는다)")
    void quotedHashKeepsQuotes() throws Exception {
        assertThat(resolve("ADMIN_PWD_HASH='" + HASH + "'")).isEqualTo("'" + HASH + "'");
        assertThat(resolve("ADMIN_PWD_HASH=\"" + HASH + "\"")).isEqualTo("\"" + HASH + "\"");
    }

    private String resolve(String line) throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, line + System.lineSeparator(), StandardCharsets.UTF_8);
        List<PropertySource<?>> sources = new PropertiesPropertySourceLoader().load("env", new FileSystemResource(env));
        StandardEnvironment environment = new StandardEnvironment();
        sources.forEach(s -> environment.getPropertySources().addFirst(s));
        return environment.resolveRequiredPlaceholders("${ADMIN_PWD_HASH:}");
    }
}
