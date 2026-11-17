package com.stockshield.market;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockshield.common.error.ApiException;
import com.stockshield.common.error.ErrorCode;
import com.stockshield.fds.risk.StockRiskJudge;
import com.stockshield.order.MarketHours;

/**
 * 시세·종목 조회 (API 명세서 'API_명세서' 10~13행, MKT_VIEW_001·002, FDS_MKT_001).
 *
 * 시세·지수·위험 의심 값은 Redis(price:·index:·risk:stock:)만 읽고, 요청 중에 외부 API를 부르지 않는다.
 * 종목명은 stock_master, 차트는 price_minute에서 읽는다. Redis 연결 실패는 공통 처리기가 503으로 응답한다.
 * 시세 지연: 가장 최근 시세 시각이 60초 넘게 지났거나 시세가 없으면 priceDelayed = true이고, 위험 의심 목록은 비운다.
 */
@Service
public class MarketQueryService {

    public static final String KOSPI = "0001";
    public static final String KOSDAQ = "1001";
    static final int TOP_N = 10;
    static final int SEARCH_LIMIT = 20;
    static final int CHART_LIMIT = 390;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'+09:00'");

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final MarketHours marketHours;

    public MarketQueryService(StringRedisTemplate redis, JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                              Clock clock, MarketHours marketHours) {
        this.redis = redis;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.marketHours = marketHours;
    }

    /**
     * 시세 지연을 판단하는 시간인가: 주문 가능 시간(평일 09:00~15:20, MarketHours.isOrderable) 안이고
     * 개장 뒤 60초가 지났을 때만. 장 마감 동시호가(15:20~15:30)와 장 밖에는 체결이 없는 게 정상이라 지연으로 보지 않는다
     * (11/17 15:22 동시호가에 전 종목이 지연으로 판정됨, 조성우 #40과 경계를 맞춤)
     */
    public boolean judgesDelay(LocalDateTime now) {
        return marketHours.isOrderable(now) && marketHours.isOrderable(now.minus(StockRiskJudge.STALE_AFTER));
    }

    // ------------------------------------------------------------------ 10행: 종목 자동완성 검색

    /** 종목코드 앞부분 또는 종목명 일부가 맞는 종목. 공백 검색어는 빈 목록 */
    public List<Map<String, Object>> search(String keyword) {
        String k = keyword == null ? "" : keyword.trim();
        if (k.isEmpty()) {
            return List.of();
        }
        String like = escapeLike(k);
        return jdbcTemplate.query("""
                        SELECT tck_cd, tck_nm FROM stock_master
                         WHERE tck_cd LIKE ? ESCAPE '\\' OR tck_nm ILIKE ? ESCAPE '\\'
                         ORDER BY CASE WHEN tck_cd = ? OR tck_nm = ? THEN 0 ELSE 1 END, tck_nm
                         LIMIT ?
                        """,
                (rs, n) -> item("tckCd", rs.getString("tck_cd"), "tckNm", rs.getString("tck_nm")),
                like + "%", "%" + like + "%", k, k, SEARCH_LIMIT);
    }

    // ------------------------------------------------------------------ 11행: 메인 대시보드

    public Map<String, Object> summary() {
        Map<String, String> names = stockNames();
        List<JsonNode> prices = readAll(PriceCache.PRICE_KEY);
        boolean delayed = isDelayed(prices);

        List<Map<String, Object>> riskStocks = delayed ? List.of() : riskList(names);
        Set<String> risky = new HashSet<>();
        riskStocks.forEach(r -> risky.add((String) r.get("tckCd")));

        Map<String, Object> ranks = new LinkedHashMap<>();
        ranks.put("rise", top(prices, names, risky, Comparator.comparingDouble((JsonNode p) -> p.path("chgRt").asDouble()).reversed()));
        ranks.put("fall", top(prices, names, risky, Comparator.comparingDouble((JsonNode p) -> p.path("chgRt").asDouble())));
        ranks.put("volume", top(prices, names, risky, Comparator.comparingLong((JsonNode p) -> p.path("totalTrdVol").asLong()).reversed()));

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("kospi", readOne(PriceCache.INDEX_KEY + KOSPI).map(MarketQueryService::indexView).orElse(null));
        s.put("kosdaq", readOne(PriceCache.INDEX_KEY + KOSDAQ).map(MarketQueryService::indexView).orElse(null));
        s.put("topRanks", ranks);
        s.put("riskStocks", riskStocks);
        s.put("priceDelayed", delayed);
        s.put("updTs", LocalDateTime.now(clock).format(ISO));
        return s;
    }

    // ------------------------------------------------------------------ 12행: 종목 상세

    public Map<String, Object> detail(String tckCd) {
        List<String[]> master = jdbcTemplate.query("SELECT tck_nm, mkt_cd FROM stock_master WHERE tck_cd = ?",
                (rs, n) -> new String[] {rs.getString("tck_nm"), rs.getString("mkt_cd")}, tckCd);
        if (master.isEmpty()) {
            throw new ApiException(ErrorCode.STOCK_NOT_FOUND, Map.of("tckCd", tckCd));
        }
        String name = master.get(0)[0];
        Optional<JsonNode> price = readOne(PriceCache.PRICE_KEY + tckCd);
        Optional<JsonNode> risk = readOne(StockRiskJudge.RISK_KEY + tckCd);

        Map<String, Object> riskView = new LinkedHashMap<>();
        boolean riskYn = risk.isPresent() && price.map(p -> !isStale(p)).orElse(false);
        riskView.put("riskYn", riskYn ? "Y" : "N");
        riskView.put("dir", riskYn ? risk.get().path("dir").asText() : null);
        riskView.put("reason", riskYn ? risk.get().path("reason").asText() : null);

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("tckCd", tckCd);
        d.put("tckNm", name);
        d.put("mktCd", master.get(0)[1]);
        d.put("currentPrice", price.map(p -> p.path("currentPrice").asLong()).orElse(null));
        d.put("chgPrc", price.map(p -> p.path("chgPrc").asLong()).orElse(null));
        d.put("chgRt", price.map(p -> p.path("chgRt").decimalValue()).orElse(null));
        d.put("totalTrdVol", price.map(p -> p.path("totalTrdVol").asLong()).orElse(null));
        d.put("prcTs", price.map(p -> p.path("prcTs").asText()).orElse(null));
        d.put("priceDelayed", price.map(this::isStale).orElse(true));
        d.put("chart", chart(tckCd));
        d.put("risk", riskView);
        return d;
    }

    /** 가장 최근 거래일의 1분봉(최대 390개), 시간순 */
    private List<Map<String, Object>> chart(String tckCd) {
        String lastMinute = jdbcTemplate.query(
                "SELECT MAX(bs_mi) FROM price_minute WHERE tck_cd = ?", rs -> rs.next() ? rs.getString(1) : null, tckCd);
        if (lastMinute == null) {
            return List.of();
        }
        String day = lastMinute.substring(0, 8);
        return jdbcTemplate.query("""
                        SELECT bs_mi, open_prc, high_prc, low_prc, close_prc, trd_vol FROM (
                            SELECT * FROM price_minute WHERE tck_cd = ? AND bs_mi LIKE ?
                             ORDER BY bs_mi DESC LIMIT ?) t
                         ORDER BY bs_mi
                        """,
                (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("bsMi", rs.getString("bs_mi"));
                    m.put("openPrc", rs.getLong("open_prc"));
                    m.put("highPrc", rs.getLong("high_prc"));
                    m.put("lowPrc", rs.getLong("low_prc"));
                    m.put("closePrc", rs.getLong("close_prc"));
                    m.put("trdVol", rs.getLong("trd_vol"));
                    return m;
                }, tckCd, day + "%", CHART_LIMIT);
    }

    // ------------------------------------------------------------------ 13행: 위험 의심 종목 목록

    public List<Map<String, Object>> riskStocks() {
        if (isDelayed(readAll(PriceCache.PRICE_KEY))) {
            return List.of();
        }
        return riskList(stockNames());
    }

    private List<Map<String, Object>> riskList(Map<String, String> names) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode r : readAll(StockRiskJudge.RISK_KEY)) {
            String code = r.path("tckCd").asText();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tckCd", code);
            m.put("tckNm", names.get(code));
            m.put("dir", r.path("dir").asText());
            m.put("chgRt", r.path("chgRt").decimalValue());
            m.put("reason", r.path("reason").asText());
            m.put("dtcTs", r.path("dtcTs").asText());
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> (String) m.get("dtcTs"), Comparator.reverseOrder()));
        return out;
    }

    // ------------------------------------------------------------------ 공통

    /** 순위 항목의 riskYn은 같은 응답의 riskStocks에 있는 종목만 Y (회신 8번 답 2) */
    private List<Map<String, Object>> top(List<JsonNode> prices, Map<String, String> names, Set<String> risky,
                                          Comparator<JsonNode> order) {
        return prices.stream().sorted(order).limit(TOP_N).map(p -> {
            String code = p.path("tckCd").asText();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tckCd", code);
            m.put("tckNm", names.get(code));
            m.put("currentPrice", p.path("currentPrice").asLong());
            m.put("chgPrc", p.path("chgPrc").asLong());
            m.put("chgRt", p.path("chgRt").decimalValue());
            m.put("totalTrdVol", p.path("totalTrdVol").asLong());
            m.put("riskYn", risky.contains(code) ? "Y" : "N");
            return m;
        }).toList();
    }

    private boolean isDelayed(List<JsonNode> prices) {
        return prices.stream().noneMatch(p -> !isStale(p));
    }

    /** 지연 판단 시간(judgesDelay)에만 60초 기준으로 본다. 그 밖에는 마지막 시세를 지연 없이 보여 준다 */
    private boolean isStale(JsonNode price) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (!judgesDelay(now)) {
            return false;
        }
        try {
            LocalDateTime ts = OffsetDateTime.parse(price.path("prcTs").asText())
                    .atZoneSameInstant(KST).toLocalDateTime();
            return isStale(ts, now);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** 60초를 넘겨야 지연. 정확히 60초는 지연이 아니다 (주문 PriceReader와 같은 기준, TRD_ORD_001 #2, SYS_CORE_001 #6) */
    public static boolean isStale(LocalDateTime prcTs, LocalDateTime now) {
        return Duration.between(prcTs, now).compareTo(StockRiskJudge.STALE_AFTER) > 0;
    }

    /** 종목 하나의 시세 상태 (관제 시세 지연 경고용) */
    public record PriceStatus(String tckCd, String tckNm, String prcTs, boolean delayed) {
    }

    /** 서비스 대상 전 종목(stock_master)의 시세 시각과 지연 여부. 시세가 없으면 지연 */
    public List<PriceStatus> priceStatuses() {
        Map<String, String> names = stockNames();
        Map<String, JsonNode> byCode = new HashMap<>();
        readAll(PriceCache.PRICE_KEY).forEach(p -> byCode.put(p.path("tckCd").asText(), p));
        List<PriceStatus> out = new ArrayList<>();
        names.forEach((code, name) -> {
            JsonNode p = byCode.get(code);
            out.add(new PriceStatus(code, name, p == null ? null : p.path("prcTs").asText(null),
                    p == null || isStale(p)));
        });
        return out;
    }

    private Map<String, String> stockNames() {
        Map<String, String> names = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT tck_cd, tck_nm FROM stock_master",
                rs -> {
                    names.put(rs.getString("tck_cd"), rs.getString("tck_nm"));
                });
        return names;
    }

    private Optional<JsonNode> readOne(String key) {
        return Optional.ofNullable(redis.opsForValue().get(key)).flatMap(this::parse);
    }

    private List<JsonNode> readAll(String prefix) {
        List<String> keys = new ArrayList<>(scanKeys(prefix));
        if (keys.isEmpty()) {
            return List.of();
        }
        List<String> values = redis.opsForValue().multiGet(keys);
        List<JsonNode> out = new ArrayList<>();
        if (values != null) {
            values.stream().filter(v -> v != null).map(this::parse).flatMap(Optional::stream).forEach(out::add);
        }
        return out;
    }

    private Set<String> scanKeys(String prefix) {
        Set<String> keys = new HashSet<>();
        try (Cursor<String> c = redis.scan(ScanOptions.scanOptions().match(prefix + "*").count(200).build())) {
            c.forEachRemaining(keys::add);
        }
        return keys;
    }

    private Optional<JsonNode> parse(String json) {
        try {
            return Optional.of(objectMapper.readTree(json));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** 지수: {currentVal, chgVal, chgRt, prcTs} */
    private static Map<String, Object> indexView(JsonNode n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("currentVal", n.path("currentVal").isMissingNode() ? null : n.path("currentVal").decimalValue());
        m.put("chgVal", n.path("chgVal").isMissingNode() || n.path("chgVal").isNull() ? null : n.path("chgVal").decimalValue());
        m.put("chgRt", n.path("chgRt").isMissingNode() ? null : n.path("chgRt").decimalValue());
        m.put("prcTs", n.path("prcTs").asText(null));
        return m;
    }

    private static Map<String, Object> item(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
