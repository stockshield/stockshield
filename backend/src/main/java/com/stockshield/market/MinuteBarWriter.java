package com.stockshield.market;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 분봉 저장 (MKT_TICK_002 #3). 체결이 올 때마다 그 분(시세 시각 기준)의 봉을 한 문장으로 갱신한다.
 * 시가는 처음 값, 고가·저가는 최대·최소, 종가는 마지막 값, 거래량은 체결 수량의 합이다.
 * 메모리에 봉을 들고 있지 않아 컨슈머가 다시 시작해도 이어서 쌓인다.
 * price_minute.tck_cd는 stock_master를 참조하므로 서비스 대상 30종목만 저장된다(연결 합의서 11절).
 * 목록에 없는 종목은 건너뛰고 종목마다 한 번만 로그를 남긴다.
 */
@Component
public class MinuteBarWriter {

    private static final Logger log = LoggerFactory.getLogger(MinuteBarWriter.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter BS_MI = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private static final String UPSERT = """
            INSERT INTO price_minute (tck_cd, bs_mi, open_prc, high_prc, low_prc, close_prc, trd_vol, reg_ts)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tck_cd, bs_mi) DO UPDATE SET
                high_prc  = GREATEST(price_minute.high_prc, EXCLUDED.high_prc),
                low_prc   = LEAST(price_minute.low_prc, EXCLUDED.low_prc),
                close_prc = EXCLUDED.close_prc,
                trd_vol   = price_minute.trd_vol + EXCLUDED.trd_vol
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final Set<String> unknownCodes = ConcurrentHashMap.newKeySet();

    public MinuteBarWriter(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /** 저장했으면 true, 서비스 대상이 아닌 종목이라 건너뛰었으면 false */
    public boolean add(MarketTick t) {
        String bsMi = OffsetDateTime.parse(t.prcTs()).atZoneSameInstant(KST).format(BS_MI);
        long price = t.currentPrice();
        long vol = t.trdVol() == null ? 0L : Math.max(0L, t.trdVol());
        try {
            jdbcTemplate.update(UPSERT, t.code(), bsMi, price, price, price, price, vol,
                    Timestamp.valueOf(LocalDateTime.now(clock)));
            return true;
        } catch (DataIntegrityViolationException e) {
            if (unknownCodes.add(t.code())) {
                log.warn("stock_master에 없는 종목이라 분봉을 저장하지 않습니다 tckCd={}", t.code());
            }
            return false;
        }
    }

    public static String minuteOf(String prcTs) {
        return OffsetDateTime.parse(prcTs).atZoneSameInstant(KST).format(BS_MI);
    }
}
