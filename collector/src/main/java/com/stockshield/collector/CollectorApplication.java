package com.stockshield.collector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * StockShield 시세 수집기. 메인 서버와 따로 실행한다 (MKT_TICK_001, 연결 합의서 3절).
 * 한국투자증권 웹소켓 체결가(또는 가짜·재생 시세)를 Kafka market.tick으로 보낸다. Redis는 쓰지 않는다
 * (Redis에는 메인 서버의 시세 컨슈머 한 곳에서만 쓴다, MKT_TICK_001 #1).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class CollectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(CollectorApplication.class, args);
    }
}
