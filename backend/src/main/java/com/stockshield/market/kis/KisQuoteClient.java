package com.stockshield.market.kis;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 한국투자증권 REST 시세 조회 (메인 서버 전용. 화면이 직접 부르면 앱 키가 노출되므로 메인 서버만 부른다).
 *
 * - 접근 토큰(/oauth2/tokenP, 유효 24시간)은 Redis kis:token에 만료 10분 전까지 두고 같이 쓴다.
 *   KIS는 토큰 발급을 1분에 1번으로 제한하므로, 서버를 다시 켜도 새로 받지 않게 한다. 발급은 Redis 잠금으로 한 번만.
 * - 실전 REST는 초당 20건 제한이 있다. 부르는 쪽(호가 캐시)이 종목별로 호출을 묶는다.
 * - 앱 키·시크릿·토큰은 로그·예외 메시지에 남기지 않는다. 실패는 KisUnavailableException 하나로 알린다.
 */
@Component
public class KisQuoteClient {

    static final String TOKEN_KEY = "kis:token";
    static final String TOKEN_LOCK_KEY = "kis:token:lock";
    static final String ORDERBOOK_PATH = "/uapi/domestic-stock/v1/quotations/inquire-asking-price-exp-ccn";
    static final String ORDERBOOK_TR = "FHKST01010200";

    private static final Logger log = LoggerFactory.getLogger(KisQuoteClient.class);

    private final RestClient rest;
    private final StringRedisTemplate redis;
    private final String appKey;
    private final String appSecret;

    public KisQuoteClient(@Value("${stockshield.kis.rest-url:https://openapi.koreainvestment.com:9443}") String restUrl,
                          @Value("${stockshield.kis.app-key:}") String appKey,
                          @Value("${stockshield.kis.app-secret:}") String appSecret,
                          @Value("${stockshield.kis.timeout:3s}") Duration timeout,
                          StringRedisTemplate redis) {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.rest = RestClient.builder().baseUrl(restUrl).requestFactory(factory).build();
        this.redis = redis;
        this.appKey = appKey == null ? "" : appKey.trim();
        this.appSecret = appSecret == null ? "" : appSecret.trim();
    }

    /** KIS를 쓸 수 없을 때(키 없음·토큰 실패·호출 실패·rt_cd 오류) */
    public static class KisUnavailableException extends RuntimeException {
        public KisUnavailableException(String message) {
            super(message);
        }
    }

    public boolean hasKey() {
        return !appKey.isEmpty() && !appSecret.isEmpty();
    }

    /** 주식현재가 호가(FHKST01010200)의 output1. 실패는 KisUnavailableException */
    public JsonNode orderbook(String tckCd) {
        JsonNode body = get(ORDERBOOK_PATH, ORDERBOOK_TR, Map.of("FID_COND_MRKT_DIV_CODE", "J", "FID_INPUT_ISCD", tckCd));
        JsonNode out = body.path("output1");
        if (!out.isObject() || out.isEmpty()) {
            throw new KisUnavailableException("호가 응답이 비어 있음");
        }
        return out;
    }

    static final String DAILY_PATH = "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice";
    static final String DAILY_TR = "FHKST03010100";

    /** 일봉 한 건 (수정주가) */
    public record DailyBar(String bsDt, long openPrc, long highPrc, long lowPrc, long closePrc, long trdVol) {
    }

    /**
     * 국내주식 기간별 시세(FHKST03010100) 일봉, 수정주가(FID_ORG_ADJ_PRC=0). 호출당 최대 100건이고 다음 페이지 표시가 없어
     * 부르는 쪽이 날짜 구간을 나눠야 한다. 종가가 0인 행(거래 없음)은 뺀다. 결과는 KIS 순서(최신부터).
     */
    public java.util.List<DailyBar> daily(String tckCd, String fromYmd, String toYmd) {
        JsonNode body = get(DAILY_PATH, DAILY_TR, Map.of("FID_COND_MRKT_DIV_CODE", "J", "FID_INPUT_ISCD", tckCd,
                "FID_INPUT_DATE_1", fromYmd, "FID_INPUT_DATE_2", toYmd, "FID_PERIOD_DIV_CODE", "D",
                "FID_ORG_ADJ_PRC", "0"));
        java.util.List<DailyBar> out = new java.util.ArrayList<>();
        for (JsonNode r : body.path("output2")) {
            String dt = r.path("stck_bsop_date").asText("");
            long close = r.path("stck_clpr").asLong(0);
            if (dt.length() != 8 || close <= 0) {
                continue;
            }
            long open = r.path("stck_oprc").asLong(0);
            long high = r.path("stck_hgpr").asLong(0);
            long low = r.path("stck_lwpr").asLong(0);
            // 시가가 0이면(단일가만 체결 등) 종가로 채운다
            out.add(new DailyBar(dt, open > 0 ? open : close, high > 0 ? high : close, low > 0 ? low : close, close,
                    r.path("acml_vol").asLong(0)));
        }
        return out;
    }

    private JsonNode get(String path, String trId, Map<String, String> params) {
        if (!hasKey()) {
            throw new KisUnavailableException("KIS 앱 키가 없음");
        }
        String token = token();
        JsonNode body;
        try {
            body = rest.get()
                    .uri(b -> {
                        b.path(path);
                        params.forEach(b::queryParam);
                        return b.build();
                    })
                    .header("authorization", "Bearer " + token)
                    .header("appkey", appKey)
                    .header("appsecret", appSecret)
                    .header("tr_id", trId)
                    .header("custtype", "P")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RuntimeException e) {
            throw new KisUnavailableException("KIS 호출 실패 " + trId + ": " + e.getClass().getSimpleName());
        }
        if (body == null || !"0".equals(body.path("rt_cd").asText())) {
            String code = body == null ? "" : body.path("msg_cd").asText("");
            if ("EGW00123".equals(code) || "EGW00121".equals(code)) {
                redis.delete(TOKEN_KEY);   // 만료·무효 토큰: 다음 요청에서 새로 받음
            }
            throw new KisUnavailableException("KIS 응답 오류 " + trId + " " + code);
        }
        return body;
    }

    /** Redis에 있으면 그대로, 없으면 한 서버만 발급해 저장한다 */
    String token() {
        String cached = redis.opsForValue().get(TOKEN_KEY);
        if (cached != null) {
            return cached;
        }
        Boolean locked = redis.opsForValue().setIfAbsent(TOKEN_LOCK_KEY, "1", 65, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(locked)) {
            // 다른 요청이 발급 중: 잠깐 기다렸다가 다시 읽는다(발급 제한 1분 1번)
            for (int i = 0; i < 30; i++) {
                sleep(100);
                cached = redis.opsForValue().get(TOKEN_KEY);
                if (cached != null) {
                    return cached;
                }
            }
            throw new KisUnavailableException("KIS 토큰 발급 대기 시간 초과");
        }
        try {
            JsonNode res = rest.post()
                    .uri("/oauth2/tokenP")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("grant_type", "client_credentials", "appkey", appKey, "appsecret", appSecret))
                    .retrieve()
                    .body(JsonNode.class);
            String token = res == null ? null : res.path("access_token").asText(null);
            if (token == null || token.isBlank()) {
                throw new KisUnavailableException("KIS 토큰 발급 실패");
            }
            long ttl = Math.max(60, res.path("expires_in").asLong(86_400) - 600);
            redis.opsForValue().set(TOKEN_KEY, token, ttl, TimeUnit.SECONDS);
            log.info("KIS 접근 토큰 발급, {}초 동안 사용", ttl);
            return token;
        } catch (KisUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            // 실패 뒤 잠금은 만료(65초)까지 둬서 1분 1번 제한을 넘지 않게 한다
            throw new KisUnavailableException("KIS 토큰 발급 실패: " + e.getClass().getSimpleName());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KisUnavailableException("중단됨");
        }
    }
}
