package com.stockshield.news;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * finlight 뉴스 API (NEWS_COL_001, 팀 결정 11/16: 한국경제 API 대신 finlight).
 *
 * POST {finlight-url}/v2/articles, 헤더 X-API-KEY. 한국어 매체(한국경제·매일경제·서울경제·조선비즈·뉴스핌·연합뉴스)는
 * language "ko"로 받는다. 무료 플랜은 월 5,000건이라 30종목을 한 번의 요청으로 묶는다.
 * 무료 플랜 응답에는 기사 본문·감성 점수·기업 정보가 없고 제목·요약·링크·발행 시각만 온다.
 * 키는 로그·예외 메시지에 남기지 않는다.
 */
@Component
public class FinlightClient {

    static final String ARTICLES_PATH = "/v2/articles";

    private final RestClient restClient;
    private final String apiKey;

    public FinlightClient(@Value("${stockshield.news.finlight-url:https://api.finlight.me}") String baseUrl,
                          @Value("${stockshield.news.finlight-api-key:}") String apiKey,
                          @Value("${stockshield.news.timeout:15s}") Duration timeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public boolean hasKey() {
        return !apiKey.isEmpty();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Article(String title, String summary, String link, String publishDate, String source,
                          String language) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ArticlesResponse(String status, List<Article> articles) {
    }

    /** 종목 코드 중 하나라도 언급한 한국어 기사(최신순). 실패는 예외 */
    public List<Article> latestKorean(List<String> tickers, int pageSize) {
        String query = String.join(" OR ", tickers.stream().map(t -> "ticker:" + t).toList());
        ArticlesResponse res = restClient.post()
                .uri(ARTICLES_PATH)
                .header("X-API-KEY", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(Map.of("query", query, "language", "ko", "pageSize", pageSize,
                        "orderBy", "publishDate", "order", "DESC"))
                .retrieve()
                .body(ArticlesResponse.class);
        return res == null || res.articles() == null ? List.of() : res.articles();
    }
}
