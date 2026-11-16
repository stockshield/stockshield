package com.stockshield.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.stockshield.fds.ai.AiServerClient;
import com.stockshield.support.IntegrationTestSupport;
import com.sun.net.httpserver.HttpServer;

class NewsCollectorTest extends IntegrationTestSupport {

    private static boolean newsUrlReady;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

    private final FinlightClient finlight = mock(FinlightClient.class);
    private final AiServerClient ai = mock(AiServerClient.class);
    private NewsCollector collector;

    /**
     * news_url은 조성우의 Flyway V5가 만든다. V5가 merge되기 전에는 테스트 DB에 여기서 임시로 만든다.
     * V5가 들어오면 IF NOT EXISTS라 아무것도 하지 않는다(그 뒤 이 블록은 지워도 됨).
     */
    @BeforeEach
    void setUp() throws Exception {
        // 앱 DB 계정에는 뉴스 삭제 권한이 없어(V2) 정리는 관리자 계정으로 한다
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement st = c.createStatement()) {
            if (!newsUrlReady) {
                st.execute("ALTER TABLE news ADD COLUMN IF NOT EXISTS news_url VARCHAR(1000)");
                newsUrlReady = true;
            }
            st.execute("DELETE FROM news_stock");
            st.execute("DELETE FROM news");
        }
        testClock.set(LocalDateTime.of(2026, 11, 17, 10, 0));
        when(finlight.hasKey()).thenReturn(true);
        collector = new NewsCollector(finlight, ai, jdbc, tx, testClock, true, true, 100, 10, 7, 20);
    }

    private static FinlightClient.Article article(String title, String summary, String link, String pub) {
        return new FinlightClient.Article(title, summary, link, pub, "www.hankyung.com", "ko");
    }

    @Test
    @DisplayName("수집: 종목이 연결된 기사만 저장하고(news·news_stock), 같은 링크는 다시 저장하지 않으며, 요약을 붙인다")
    void collectsLinksDedupsAndSummarizes() {
        when(finlight.latestKorean(anyList(), anyInt())).thenReturn(List.of(
                article("삼성전자가 HBM 증산", "삼성전자(005930)는 내년 HBM 생산을 늘린다", "https://www.hankyung.com/a/1",
                        "2026-11-17T00:30:00.000Z"),
                article("[표] 외국인 순매수 상위", "삼성전자 SK하이닉스", "https://www.yna.co.kr/t/1", "2026-11-17T00:20:00.000Z"),
                article("금리 동결 전망", "한국은행이 기준금리를 동결할 전망이다", "https://www.mk.co.kr/b/1",
                        "2026-11-17T00:10:00.000Z")));
        when(ai.summarize(anyString(), anyString(), anyString()))
                .thenReturn(new AiServerClient.NewsSummary("HBM 생산을 늘린다.\n수요가 강하다.", new BigDecimal("0.62")));

        NewsCollector.BatchResult first = collector.runOnce();
        assertThat(first.fetched()).isEqualTo(3);
        assertThat(first.saved()).isEqualTo(1);
        assertThat(first.summarized()).isEqualTo(1);

        String id = NewsCollector.newsId("https://www.hankyung.com/a/1");
        assertThat(jdbc.queryForList("SELECT tck_cd FROM news_stock WHERE news_id = ?", String.class, id))
                .containsExactly("005930");
        assertThat(jdbc.queryForMap("SELECT news_url, pub_ts, ai_sntm_scr FROM news WHERE news_id = ?", id))
                .containsEntry("news_url", "https://www.hankyung.com/a/1")
                .containsEntry("pub_ts", java.sql.Timestamp.valueOf(LocalDateTime.of(2026, 11, 17, 9, 30)))
                .containsEntry("ai_sntm_scr", new BigDecimal("0.62"));

        NewsCollector.BatchResult second = collector.runOnce();
        assertThat(second.saved()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("요약 실패: 비워 두고 다음 배치에서 다시 요청해 채운다 (NEWS_AI_001 #3)")
    void retriesSummaryNextBatch() {
        when(finlight.latestKorean(anyList(), anyInt())).thenReturn(List.of(
                article("기아 신차 공개", null, "https://biz.chosun.com/c/1", "2026-11-17T00:40:00.000Z")));
        String id = NewsCollector.newsId("https://biz.chosun.com/c/1");
        when(ai.summarize(eq(id), anyString(), anyString()))
                .thenThrow(new IllegalStateException("AI 서버 오류"))
                .thenReturn(new AiServerClient.NewsSummary("기아가 신차를 공개했다.", new BigDecimal("1.50")));

        assertThat(collector.runOnce().summarized()).isZero();
        assertThat(jdbc.queryForObject("SELECT ai_smry_cont FROM news WHERE news_id = ?", String.class, id)).isNull();
        // 본문이 없으면 제목을 본문 칸에 넣는다
        assertThat(jdbc.queryForObject("SELECT news_cont FROM news WHERE news_id = ?", String.class, id))
                .isEqualTo("기아 신차 공개");

        assertThat(collector.runOnce().summarized()).isEqualTo(1);
        // 감성 점수는 -1 ~ 1로 자른다
        assertThat(jdbc.queryForObject("SELECT ai_sntm_scr FROM news WHERE news_id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("1.00");
    }

    @Test
    @DisplayName("키가 없으면 수집하지 않는다. 수집 시간은 평일 07~20시")
    void skipsWithoutKeyAndOutsideHours() {
        when(finlight.hasKey()).thenReturn(false);
        assertThat(collector.runOnce().fetched()).isZero();
        assertThat(collector.inActiveHours(LocalDateTime.of(2026, 11, 17, 6, 59))).isFalse();
        assertThat(collector.inActiveHours(LocalDateTime.of(2026, 11, 17, 7, 0))).isTrue();
        assertThat(collector.inActiveHours(LocalDateTime.of(2026, 11, 17, 20, 0))).isFalse();
        assertThat(collector.inActiveHours(LocalDateTime.of(2026, 11, 21, 10, 0))).isFalse();   // 토요일
    }

    @Test
    @DisplayName("종목 뉴스 API: 최신 5건을 발행 시각 최신순으로, 요약 전 기사는 요약이 null, 없는 종목은 404")
    void stockNewsApi() throws Exception {
        List<FinlightClient.Article> list = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            list.add(article("삼성전자 소식 " + i, null, "https://www.sedaily.com/n/" + i,
                    String.format("2026-11-17T00:%02d:00.000Z", i)));
        }
        when(finlight.latestKorean(anyList(), anyInt())).thenReturn(list);
        when(ai.summarize(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("요약하지 않음"));
        collector.runOnce();

        mockMvc.perform(get("/api/v1/news/stocks/005930"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].newsTitl").value("삼성전자 소식 6"))
                .andExpect(jsonPath("$[0].newsUrl").value("https://www.sedaily.com/n/6"))
                .andExpect(jsonPath("$[0].pubTs").value("2026-11-17T09:06:00+09:00"))
                .andExpect(jsonPath("$[0].aiSmryCont").doesNotExist())
                .andExpect(jsonPath("$[4].newsTitl").value("삼성전자 소식 2"));
        mockMvc.perform(get("/api/v1/news/stocks/000660"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/v1/news/stocks/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STOCK_NOT_FOUND"));
    }

    @Test
    @DisplayName("finlight 요청: X-API-KEY 헤더, 종목 코드 OR 검색, 한국어(ko), 최신순")
    void finlightRequestFormat() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> key = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/articles", ex -> {
            key.set(ex.getRequestHeaders().getFirst("X-API-KEY"));
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] res = ("{\"status\":\"ok\",\"articles\":[{\"title\":\"삼성전자 뉴스\",\"summary\":\"요약\","
                    + "\"link\":\"https://x/1\",\"publishDate\":\"2026-11-17T00:00:00.000Z\",\"source\":\"x\","
                    + "\"language\":\"ko\",\"categories\":[\"business\"]}]}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, res.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(res);
            }
        });
        server.start();
        try {
            FinlightClient client = new FinlightClient("http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-key", Duration.ofSeconds(5));
            List<FinlightClient.Article> got = client.latestKorean(List.of("005930", "000660"), 50);
            assertThat(got).singleElement().satisfies(a -> {
                assertThat(a.title()).isEqualTo("삼성전자 뉴스");
                assertThat(a.link()).isEqualTo("https://x/1");
            });
            assertThat(key.get()).isEqualTo("test-key");
            assertThat(body.get()).contains("\"query\":\"ticker:005930 OR ticker:000660\"")
                    .contains("\"language\":\"ko\"").contains("\"pageSize\":50").contains("\"order\":\"DESC\"");
        } finally {
            server.stop(0);
        }
    }
}
