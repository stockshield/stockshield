package com.stockshield.market;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockshield.common.error.ApiException;
import com.stockshield.common.error.ErrorCode;

/**
 * 일·주·월봉 (차트·호가창 합의 4절 계약).
 *
 * GET /api/v1/market/stocks/{tckCd}/candles?interval=D|W|M →
 * {tckCd, interval, candles:[{bsDt, openPrc, highPrc, lowPrc, closePrc, trdVol}]} (오래된 것부터, 저장된 전체)
 * - D: price_daily + 오늘 진행 중인 봉(오늘 공식 일봉이 아직 없을 때만). 오늘 봉은 오늘 price_minute를 묶고
 *   종가는 Redis 현재 시세로 맞춘다(마지막 봉 종가 = 화면 현재가). 장 전·휴장일엔 오늘 1분봉이 없어 직전 거래일까지.
 * - W: 일봉을 월~금 주 단위로 묶음, M: 달력 월로 묶음. bsDt는 그 주·그 달의 첫 거래일.
 *   묶을 때 시가 = 첫 봉 시가, 고가·저가 = 최고·최저, 종가 = 마지막 봉 종가, 거래량 = 합.
 * - interval이 D·W·M이 아니면 400 INVALID_REQUEST, 서비스 대상 30종목 밖이면 404 STOCK_NOT_FOUND.
 */
@Service
public class CandleService {

    static final Set<String> INTERVALS = Set.of("D", "W", "M");
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Clock clock;

    public CandleService(JdbcTemplate jdbc, StringRedisTemplate redis, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.json = json;
        this.clock = clock;
    }

    /** 봉 하나 (계약 필드 이름 그대로) */
    public record Candle(String bsDt, long openPrc, long highPrc, long lowPrc, long closePrc, long trdVol) {
    }

    public Map<String, Object> candles(String tckCd, String interval) {
        if (interval == null || !INTERVALS.contains(interval)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, Map.of("interval", String.valueOf(interval)));
        }
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM stock_master WHERE tck_cd = ?", Integer.class, tckCd);
        if (exists == null || exists == 0) {
            throw new ApiException(ErrorCode.STOCK_NOT_FOUND, Map.of("tckCd", tckCd));
        }
        List<Candle> daily = daily(tckCd);
        List<Candle> out = switch (interval) {
            case "W" -> group(daily, d -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).format(YMD));
            case "M" -> group(daily, d -> d.withDayOfMonth(1).format(YMD));
            default -> daily;
        };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tckCd", tckCd);
        body.put("interval", interval);
        body.put("candles", out);
        return body;
    }

    /** 저장된 일봉 + 오늘 진행 중인 봉 */
    List<Candle> daily(String tckCd) {
        List<Candle> rows = new ArrayList<>(jdbc.query("""
                SELECT bs_dt, open_prc, high_prc, low_prc, close_prc, trd_vol FROM price_daily
                WHERE tck_cd = ? ORDER BY bs_dt
                """, (rs, n) -> new Candle(rs.getString("bs_dt"), rs.getLong("open_prc"), rs.getLong("high_prc"),
                rs.getLong("low_prc"), rs.getLong("close_prc"), rs.getLong("trd_vol")), tckCd));
        String today = LocalDate.now(clock).format(YMD);
        if (!rows.isEmpty() && rows.get(rows.size() - 1).bsDt().compareTo(today) >= 0) {
            return rows;   // 오늘 공식 일봉이 이미 있음(장 마감 뒤)
        }
        Candle live = todayFromMinutes(tckCd, today);
        if (live != null) {
            rows.add(live);
        }
        return rows;
    }

    /** 오늘 1분봉을 묶은 진행 중인 봉. 종가·고가·저가는 Redis 현재 시세(오늘 것일 때)로 맞춘다. 오늘 1분봉이 없으면 null */
    Candle todayFromMinutes(String tckCd, String today) {
        List<Map<String, Object>> agg = jdbc.queryForList("""
                SELECT (ARRAY_AGG(open_prc ORDER BY bs_mi))[1] AS o, MAX(high_prc) AS h, MIN(low_prc) AS l,
                       (ARRAY_AGG(close_prc ORDER BY bs_mi DESC))[1] AS c, COALESCE(SUM(trd_vol), 0) AS v, COUNT(*) AS n
                FROM price_minute WHERE tck_cd = ? AND bs_mi LIKE ?
                """, tckCd, today + "%");
        Map<String, Object> a = agg.get(0);
        if (((Number) a.get("n")).longValue() == 0) {
            return null;
        }
        long o = ((Number) a.get("o")).longValue();
        long h = ((Number) a.get("h")).longValue();
        long l = ((Number) a.get("l")).longValue();
        long c = ((Number) a.get("c")).longValue();
        long v = ((Number) a.get("v")).longValue();
        Long current = currentPriceToday(tckCd, today);
        if (current != null) {
            c = current;
            h = Math.max(h, current);
            l = Math.min(l, current);
        }
        return new Candle(today, o, h, l, c, v);
    }

    private Long currentPriceToday(String tckCd, String today) {
        String raw = redis.opsForValue().get(PriceCache.PRICE_KEY + tckCd);
        if (raw == null) {
            return null;
        }
        try {
            JsonNode p = json.readTree(raw);
            String day = OffsetDateTime.parse(p.path("prcTs").asText()).atZoneSameInstant(KST).toLocalDate().format(YMD);
            long price = p.path("currentPrice").asLong(0);
            return day.equals(today) && price > 0 ? price : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 일봉을 키(주 월요일·달 1일)로 묶는다. bsDt는 묶음의 첫 거래일 */
    static List<Candle> group(List<Candle> daily, Function<LocalDate, String> keyOf) {
        Map<String, List<Candle>> groups = new LinkedHashMap<>();
        for (Candle d : daily) {
            groups.computeIfAbsent(keyOf.apply(LocalDate.parse(d.bsDt(), YMD)), k -> new ArrayList<>()).add(d);
        }
        List<Candle> out = new ArrayList<>(groups.size());
        for (List<Candle> g : groups.values()) {
            Candle first = g.get(0);
            Candle lastBar = g.get(g.size() - 1);
            long high = g.stream().mapToLong(Candle::highPrc).max().orElse(first.highPrc());
            long low = g.stream().mapToLong(Candle::lowPrc).min().orElse(first.lowPrc());
            long vol = g.stream().mapToLong(Candle::trdVol).sum();
            out.add(new Candle(first.bsDt(), first.openPrc(), high, low, lastBar.closePrc(), vol));
        }
        return out;
    }
}
