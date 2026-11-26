package com.stockshield.fds.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * AI 서버(FastAPI) 내부 API 호출 (API 명세서 'API_명세서' 16행, FDS_AI_001).
 *
 * POST {base-url}/api/v1/internal/ai/detection-reason
 * 요청 {dtcSeq, ruleCd, rsltCd, facts}, 응답 {aiRsnCont, aiChkCont}. 10초 안에 답이 없거나 실패하면 예외를 던진다(#4).
 */
@Component
public class AiServerClient {

    static final String DETECTION_REASON_PATH = "/api/v1/internal/ai/detection-reason";

    private final RestClient restClient;

    public AiServerClient(@Value("${stockshield.ai.base-url:http://localhost:8000}") String baseUrl,
                          @Value("${stockshield.ai.timeout:10s}") Duration timeout,
                          @Value("${stockshield.ai.connect-timeout:2s}") Duration connectTimeout) {
        // AI 서버(uvicorn)는 평문 HTTP/2 업그레이드(Upgrade: h2c)를 받지 않고 400을 돌려준다(11/17 배포에서 확인).
        // JDK HttpClient 기본값은 HTTP/2라 본문이 있는 요청에 업그레이드 헤더를 붙이므로 HTTP/1.1로 고정한다
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public record DetectionReason(String aiRsnCont, String aiChkCont) {
    }

    static final String SUMMARY_PATH = "/api/v1/internal/ai/summary";

    /** 뉴스 요약 응답 (API 명세서 15행): 3문장 이내 요약(줄바꿈 구분), 감성 점수 -1.00 ~ 1.00 */
    public record NewsSummary(String aiSmryCont, java.math.BigDecimal aiSntmScr) {
    }

    /** 뉴스 3줄 요약·감성 점수 (NEWS_AI_001). 실패(연결·시간 초과·4xx·5xx·빈 응답)는 모두 예외 */
    public NewsSummary summarize(String newsId, String newsTitl, String newsCont) {
        NewsSummary r = restClient.post()
                .uri(SUMMARY_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("newsId", newsId, "newsTitl", newsTitl, "newsCont", newsCont))
                .retrieve()
                .body(NewsSummary.class);
        if (r == null || r.aiSmryCont() == null || r.aiSmryCont().isBlank() || r.aiSntmScr() == null) {
            throw new IllegalStateException("AI 서버 요약 응답이 비어 있습니다");
        }
        return r;
    }

    /** 실패(연결·시간 초과·4xx·5xx·빈 응답)는 모두 예외 */
    public DetectionReason explain(long dtcSeq, String ruleCd, String rsltCd, Map<String, Object> facts) {
        DetectionReason r = restClient.post()
                .uri(DETECTION_REASON_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("dtcSeq", dtcSeq, "ruleCd", ruleCd, "rsltCd", rsltCd, "facts", facts))
                .retrieve()
                .body(DetectionReason.class);
        if (r == null || r.aiRsnCont() == null || r.aiRsnCont().isBlank()) {
            throw new IllegalStateException("AI 서버 응답이 비어 있습니다");
        }
        return r;
    }
}
