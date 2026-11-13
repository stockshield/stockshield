package com.stockshield.collector;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 수집기 설정 (application.yml의 collector.*) */
@ConfigurationProperties(prefix = "collector")
public record CollectorProperties(String mode, boolean demoMode, String topic, Kis kis, Record record,
                                  Replay replay, List<Stock> stocks) {

    public enum Mode { KIS, MOCK, REPLAY }

    /** 한국투자증권 Open API. 키는 로그·예외 메시지에 남기지 않는다 */
    public record Kis(String env, String appKey, String appSecret, String restUrl, String wsUrl,
                      Duration reconnectDelay) {
        public boolean hasKey() {
            return appKey != null && !appKey.isBlank() && appSecret != null && !appSecret.isBlank();
        }

        @Override
        public String toString() {
            return "Kis[env=" + env + ", restUrl=" + restUrl + ", wsUrl=" + wsUrl + ", key=" + (hasKey() ? "****" : "none") + "]";
        }
    }

    public record Record(boolean enabled, String dir) {
    }

    public record Replay(String file, double speed) {
    }

    public record Stock(String code, String name, String market) {
    }
}
