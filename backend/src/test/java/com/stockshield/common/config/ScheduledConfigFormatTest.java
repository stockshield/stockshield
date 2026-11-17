package com.stockshield.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * 테스트에서는 @Scheduled 작업을 꺼 두므로(stockshield.scheduling.enabled=false), 시간 문자열 형식 오류가
 * 운영 기동에서야 드러난다(11/17 배포 때 "10m"으로 기동 실패). @Scheduled에 들어가는 설정값을 여기서 미리 확인한다.
 * Spring의 @Scheduled는 ISO-8601 기간(PT10M)이나 밀리초 숫자만 받는다.
 */
class ScheduledConfigFormatTest {

    private static Properties yaml() {
        YamlPropertiesFactoryBean f = new YamlPropertiesFactoryBean();
        f.setResources(new ClassPathResource("application.yml"));
        return f.getObject();
    }

    @Test
    @DisplayName("뉴스 수집 간격·첫 실행 지연은 @Scheduled가 읽을 수 있는 ISO-8601 형식이다")
    void newsScheduleIsIso8601() {
        Properties p = yaml();
        for (String key : new String[] {"stockshield.news.interval", "stockshield.news.initial-delay"}) {
            String v = p.getProperty(key);
            assertThat(v).as(key).isNotBlank();
            assertThatCode(() -> Duration.parse(v)).as(key + "=" + v).doesNotThrowAnyException();
        }
        assertThat(Duration.parse(p.getProperty("stockshield.news.interval"))).isEqualTo(Duration.ofMinutes(10));
    }
}
