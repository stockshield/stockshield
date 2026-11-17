package com.stockshield.market;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.stockshield.market.kis.KisQuoteClient;
import com.stockshield.market.kis.KisQuoteClient.DailyBar;

/**
 * 일봉 적재 (차트·호가창 합의 3절). 차트 요청 때마다 KIS를 부르지 않도록 price_daily에 저장해 두고 읽는다.
 *
 * - 처음(그 종목 행이 없을 때): 최근 3년 일봉을 KIS 기간별 시세(수정주가)로 채운다. 30종목 × 약 8번 호출.
 * - 그 뒤: 마지막 저장일부터 오늘까지 다시 받아 빠진 날을 채우고 마지막 날도 공식 값으로 덮어쓴다(UPSERT).
 *   그래서 수집기·서버가 하루 꺼져 있어도 다음 실행 때 그 날짜가 채워진다.
 * - 실행: 평일 16:10(장 마감 뒤 공식 일봉, EC2는 16:30에 꺼짐), 그리고 서버가 켜질 때 한 번(전날 실패분 채움).
 * - 오늘 행은 장 마감(15:30) 10분 뒤부터만 저장한다. 그 전에는 KIS가 주는 오늘 행이 진행 중인 값이라 버린다.
 * - KIS 호출 제한(실전 초당 20건)을 넘지 않게 호출 사이 70ms를 쉰다. 실패한 종목은 로그만 남기고 다음 실행에서 다시.
 */
@Component
public class DailyCandleLoader {

    static final int YEARS = 3;
    /** 한 번에 100건까지 오므로 거래일 100일이 넘지 않는 달력 일수 */
    static final int WINDOW_DAYS = 130;
    static final LocalTime STORE_TODAY_AFTER = LocalTime.of(15, 40);

    private static final Logger log = LoggerFactory.getLogger(DailyCandleLoader.class);
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    private final KisQuoteClient kis;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final boolean runOnStartup;
    private final long pauseMs;

    public DailyCandleLoader(KisQuoteClient kis, JdbcTemplate jdbc, Clock clock,
                             @Value("${stockshield.scheduling.enabled:true}") boolean runOnStartup,
                             @Value("${stockshield.candles.pause-ms:70}") long pauseMs) {
        this.kis = kis;
        this.jdbc = jdbc;
        this.clock = clock;
        this.runOnStartup = runOnStartup;
        this.pauseMs = pauseMs;
    }

    public record Result(int stocks, int calls, int saved, int failed) {
    }

    /** 서버가 켜질 때 한 번 (별도 스레드, 기동을 막지 않음). 테스트는 스케줄을 꺼 두어 돌지 않는다 */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!runOnStartup || !kis.hasKey()) {
            return;
        }
        Thread t = new Thread(() -> {
            Result r = loadAll();
            log.info("일봉 적재(기동): 종목 {}개, 호출 {}번, 저장 {}행, 실패 종목 {}개", r.stocks(), r.calls(), r.saved(), r.failed());
        }, "daily-candle-startup");
        t.setDaemon(true);
        t.start();
    }

    /** 평일 16:10 장 마감 뒤 공식 일봉 */
    @Scheduled(cron = "0 10 16 * * MON-FRI", zone = "Asia/Seoul")
    public void afterClose() {
        if (!kis.hasKey()) {
            return;
        }
        Result r = loadAll();
        log.info("일봉 적재(장 마감): 종목 {}개, 호출 {}번, 저장 {}행, 실패 종목 {}개", r.stocks(), r.calls(), r.saved(), r.failed());
    }

    public synchronized Result loadAll() {
        List<String> codes = jdbc.queryForList("SELECT tck_cd FROM stock_master ORDER BY tck_cd", String.class);
        int calls = 0;
        int saved = 0;
        int failed = 0;
        for (String code : codes) {
            try {
                int[] r = fill(code);
                calls += r[0];
                saved += r[1];
            } catch (RuntimeException e) {
                failed++;
                log.warn("일봉 적재 실패, 다음 실행에서 다시 tckCd={}: {}", code, e.getMessage());
            }
        }
        return new Result(codes.size(), calls, saved, failed);
    }

    /** 한 종목: 마지막 저장일(없으면 3년 전)부터 오늘까지 구간을 나눠 받아 UPSERT. [호출 수, 저장 행 수] */
    int[] fill(String tckCd) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        String last = jdbc.queryForObject("SELECT MAX(bs_dt) FROM price_daily WHERE tck_cd = ?", String.class, tckCd);
        LocalDate from = last == null ? today.minusYears(YEARS) : LocalDate.parse(last, YMD);
        boolean storeToday = !now.toLocalTime().isBefore(STORE_TODAY_AFTER);
        int calls = 0;
        int saved = 0;
        for (LocalDate start = from; !start.isAfter(today); start = start.plusDays(WINDOW_DAYS)) {
            LocalDate end = start.plusDays(WINDOW_DAYS - 1L);
            if (end.isAfter(today)) {
                end = today;
            }
            if (calls > 0) {
                pause();
            }
            List<DailyBar> bars = kis.daily(tckCd, start.format(YMD), end.format(YMD));
            calls++;
            for (DailyBar b : bars) {
                if (b.bsDt().compareTo(today.format(YMD)) > 0
                        || (b.bsDt().equals(today.format(YMD)) && !storeToday)) {
                    continue;
                }
                saved += jdbc.update("""
                        INSERT INTO price_daily (tck_cd, bs_dt, open_prc, high_prc, low_prc, close_prc, trd_vol, reg_ts)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (tck_cd, bs_dt) DO UPDATE SET open_prc = EXCLUDED.open_prc,
                            high_prc = EXCLUDED.high_prc, low_prc = EXCLUDED.low_prc, close_prc = EXCLUDED.close_prc,
                            trd_vol = EXCLUDED.trd_vol, reg_ts = EXCLUDED.reg_ts
                        """, tckCd, b.bsDt(), b.openPrc(), b.highPrc(), b.lowPrc(), b.closePrc(), b.trdVol(), now);
            }
        }
        return new int[] {calls, saved};
    }

    private void pause() {
        if (pauseMs <= 0) {
            return;
        }
        try {
            Thread.sleep(pauseMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
