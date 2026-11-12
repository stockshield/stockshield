package com.stockshield;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class StockshieldApplication {

    public static void main(String[] args) {
        // SYS_ENV_001 #9: 서버 JVM 시간대를 Asia/Seoul로 고정
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        SpringApplication.run(StockshieldApplication.class, args);
    }
}
