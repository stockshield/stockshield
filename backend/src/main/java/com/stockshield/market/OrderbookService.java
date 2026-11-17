package com.stockshield.market;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockshield.common.error.ApiException;
import com.stockshield.common.error.ErrorCode;
import com.stockshield.market.kis.KisQuoteClient;
import com.stockshield.order.MarketHours;

/**
 * 종목 호가 (차트·호가창 합의 5절, 11/17 필드 확정). 화면 표시용이며 주문 체결가는 지금처럼 Redis 현재 시세 기준이다.
 *
 * GET /api/v1/market/stocks/{tckCd}/orderbook →
 * {tckCd, aspTs, mktOpenYn, asks:[{lvl,prc,rsqn}…10], bids:[…10], totAskRsqn, totBidRsqn}
 * - asks(매도)·bids(매수) 모두 1단계(현재가에 가까운 쪽)부터. 가격이 0인 단계(상·하한가 등)는 뺀다.
 * - 요청 때 KIS 호가 REST를 대신 부르고 종목별 Redis에 둔다: 장중 2초, 장 밖 60초.
 *   같은 종목은 Redis 잠금으로 캐시 주기당 KIS를 1번만 부른다. 다른 요청은 잠깐 기다렸다가 캐시를 읽는다.
 * - 장 마감 뒤에는 마지막 호가를 그대로 보여 주고 mktOpenYn=N.
 * - KIS 실패: 마지막으로 받은 호가가 있으면 그것을, 없으면 503 ORDERBOOK_UNAVAILABLE(화면 '호가 일시 중단').
 * - 서비스 대상 30종목 밖이면 404 STOCK_NOT_FOUND.
 */
@Service
public class OrderbookService {

    static final String CACHE_KEY = "orderbook:";
    static final String LAST_KEY = "orderbook:last:";
    static final String LOCK_KEY = "orderbook:lock:";
    static final int LEVELS = 10;

    private static final Logger log = LoggerFactory.getLogger(OrderbookService.class);
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'+09:00'");

    private final KisQuoteClient kis;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final MarketHours marketHours;
    private final Clock clock;
    private final ObjectMapper json;
    private final Duration openTtl;
    private final Duration closedTtl;

    public OrderbookService(KisQuoteClient kis, StringRedisTemplate redis, JdbcTemplate jdbc, MarketHours marketHours,
                            Clock clock, ObjectMapper json,
                            @Value("${stockshield.orderbook.open-ttl:PT2S}") Duration openTtl,
                            @Value("${stockshield.orderbook.closed-ttl:PT60S}") Duration closedTtl) {
        this.kis = kis;
        this.redis = redis;
        this.jdbc = jdbc;
        this.marketHours = marketHours;
        this.clock = clock;
        this.json = json;
        this.openTtl = openTtl;
        this.closedTtl = closedTtl;
    }

    public Map<String, Object> orderbook(String tckCd) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM stock_master WHERE tck_cd = ?", Integer.class, tckCd);
        if (exists == null || exists == 0) {
            throw new ApiException(ErrorCode.STOCK_NOT_FOUND, Map.of("tckCd", tckCd));
        }
        Map<String, Object> cached = read(CACHE_KEY + tckCd);
        if (cached != null) {
            return cached;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        boolean open = marketHours.isOpen(now);
        Duration ttl = open ? openTtl : closedTtl;
        Boolean mine = redis.opsForValue().setIfAbsent(LOCK_KEY + tckCd, "1", ttl.toMillis(), TimeUnit.MILLISECONDS);
        if (!Boolean.TRUE.equals(mine)) {
            // 이번 주기에 다른 요청이 KIS를 부르는 중: 잠깐 기다렸다가 그 결과를 쓴다
            for (int i = 0; i < 20; i++) {
                sleep(100);
                cached = read(CACHE_KEY + tckCd);
                if (cached != null) {
                    return cached;
                }
            }
            return lastOrUnavailable(tckCd, "같은 종목 호가를 다른 요청이 가져오는 중");
        }
        try {
            // 완료 기준 확인용: 같은 종목을 여러 명이 봐도 이 줄은 캐시 주기(장중 2초)당 한 번만 찍혀야 한다
            log.info("KIS 호가 조회 tckCd={} cacheTtl={}s", tckCd, ttl.toSeconds());
            Map<String, Object> book = toResponse(tckCd, kis.orderbook(tckCd), now, open);
            String body = json.writeValueAsString(book);
            redis.opsForValue().set(CACHE_KEY + tckCd, body, ttl.toMillis(), TimeUnit.MILLISECONDS);
            redis.opsForValue().set(LAST_KEY + tckCd, body, 1, TimeUnit.DAYS);
            return book;
        } catch (KisQuoteClient.KisUnavailableException e) {
            log.warn("호가 조회 실패 tckCd={}: {}", tckCd, e.getMessage());
            return lastOrUnavailable(tckCd, e.getMessage());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** KIS 실패 때: 마지막으로 받은 호가(장 상태만 지금 기준으로)를, 그것도 없으면 503 */
    private Map<String, Object> lastOrUnavailable(String tckCd, String reason) {
        Map<String, Object> last = read(LAST_KEY + tckCd);
        if (last != null) {
            last.put("mktOpenYn", marketHours.isOpen(LocalDateTime.now(clock)) ? "Y" : "N");
            return last;
        }
        throw new ApiException(ErrorCode.ORDERBOOK_UNAVAILABLE, Map.of("tckCd", tckCd));
    }

    static Map<String, Object> toResponse(String tckCd, JsonNode o, LocalDateTime now, boolean open) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tckCd", tckCd);
        m.put("aspTs", aspTs(o.path("aspr_acpt_hour").asText(""), now));
        m.put("mktOpenYn", open ? "Y" : "N");
        m.put("asks", levels(o, "askp"));
        m.put("bids", levels(o, "bidp"));
        m.put("totAskRsqn", o.path("total_askp_rsqn").asLong(0));
        m.put("totBidRsqn", o.path("total_bidp_rsqn").asLong(0));
        return m;
    }

    private static List<Map<String, Object>> levels(JsonNode o, String side) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 1; i <= LEVELS; i++) {
            long prc = o.path(side + i).asLong(0);
            if (prc <= 0) {
                continue;
            }
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("lvl", i);
            l.put("prc", prc);
            l.put("rsqn", o.path(side + "_rsqn" + i).asLong(0));
            out.add(l);
        }
        return out;
    }

    /** 호가 접수 시각(HHMMSS)에 날짜를 붙인다. 지금보다 늦은 시각이면 전날 호가(장 전·휴장일)로 본다 */
    static String aspTs(String hhmmss, LocalDateTime now) {
        try {
            LocalTime t = LocalTime.parse(hhmmss, HHMMSS);
            LocalDate d = t.isAfter(now.toLocalTime().plusMinutes(1)) ? now.toLocalDate().minusDays(1) : now.toLocalDate();
            return LocalDateTime.of(d, t).format(ISO);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Map<String, Object> read(String key) {
        String v = redis.opsForValue().get(key);
        if (v == null) {
            return null;
        }
        try {
            return json.readValue(v, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
