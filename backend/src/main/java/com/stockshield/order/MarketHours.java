package com.stockshield.order;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 장 운영 시간: 휴장일을 뺀 평일 09:00:00 이상 15:30:00 미만 (TRD_ORD_001 #2, 테이블 정의서 '코드 정의' 21행) */
@Component
public class MarketHours {

    static final LocalTime OPEN = LocalTime.of(9, 0);
    static final LocalTime CLOSE = LocalTime.of(15, 30);

    private final boolean demoMode;
    private final Set<LocalDate> holidays;

    /** 휴장일 형식이 틀리면 기동 실패(조용히 무시하면 휴장일에 주문이 체결됨) */
    public MarketHours(@Value("${stockshield.demo-mode:false}") boolean demoMode,
                       @Value("${stockshield.market.holidays:}") String holidays) {
        this.demoMode = demoMode;
        this.holidays = Arrays.stream(holidays.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(LocalDate::parse)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 시연 모드에서는 검사하지 않음(재생 시세로 주문, MKT_TICK_001 #2) */
    public boolean isOpen(LocalDateTime now) {
        if (demoMode) {
            return true;
        }
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY || holidays.contains(now.toLocalDate())) {
            return false;
        }
        LocalTime time = now.toLocalTime();
        return !time.isBefore(OPEN) && time.isBefore(CLOSE);
    }
}
