package com.stockshield.news;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.stockshield.fds.ai.AiServerClient;

/**
 * 뉴스 수집 배치 (NEWS_COL_001)와 AI 요약 연결 (NEWS_AI_001).
 *
 * 10분마다(평일 07~20시, 무료 플랜 월 5,000건 한도 안):
 * 1) finlight에서 서비스 대상 종목(stock_master)을 언급한 최신 한국어 기사를 한 번의 요청으로 받는다.
 * 2) 제목·요약의 종목명·종목 코드로 관련 종목을 찾고(StockMatcher), 종목이 없는 기사는 버린다.
 * 3) 원문 링크로 기사 번호(FL-+SHA-1 40자)를 만들어 이미 있으면 건너뛴다(중복 제거). news·news_stock에 저장한다.
 *    finlight 무료 플랜은 본문을 주지 않아 news_cont에는 기사 요약(없으면 제목)을 넣는다.
 * 4) 아직 요약이 없는 기사를 최신순으로 한 번에 최대 summarize-per-batch건 AI 서버로 요약한다.
 *    실패하면 비워 두고 다음 배치에서 다시 요청한다(NEWS_AI_001 #3).
 * 수집·요약 실패는 로그만 남긴다. 키가 없으면 수집을 건너뛴다.
 */
@Component
public class NewsCollector {

    private static final Logger log = LoggerFactory.getLogger(NewsCollector.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final FinlightClient finlight;
    private final AiServerClient aiClient;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final boolean enabled;
    private final boolean aiEnabled;
    private final int pageSize;
    private final int summarizePerBatch;
    private final int startHour;
    private final int endHour;

    public NewsCollector(FinlightClient finlight, AiServerClient aiClient, JdbcTemplate jdbc, TransactionTemplate tx,
                         Clock clock,
                         @Value("${stockshield.news.enabled:true}") boolean enabled,
                         @Value("${stockshield.ai.enabled:true}") boolean aiEnabled,
                         @Value("${stockshield.news.page-size:100}") int pageSize,
                         @Value("${stockshield.news.summarize-per-batch:10}") int summarizePerBatch,
                         @Value("${stockshield.news.start-hour:7}") int startHour,
                         @Value("${stockshield.news.end-hour:20}") int endHour) {
        this.finlight = finlight;
        this.aiClient = aiClient;
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.enabled = enabled;
        this.aiEnabled = aiEnabled;
        this.pageSize = pageSize;
        this.summarizePerBatch = summarizePerBatch;
        this.startHour = startHour;
        this.endHour = endHour;
    }

    public record BatchResult(int fetched, int saved, int summarized) {
    }

    // @Scheduled의 시간 문자열은 ISO-8601(PT10M)이나 밀리초 숫자만 받는다. "10m" 같은 줄임 표기는 기동 실패
    @Scheduled(fixedDelayString = "${stockshield.news.interval:PT10M}", initialDelayString = "${stockshield.news.initial-delay:PT1M}")
    public void scheduled() {
        if (!enabled || !inActiveHours(LocalDateTime.now(clock))) {
            return;
        }
        try {
            BatchResult r = runOnce();
            log.info("뉴스 배치: 받은 기사 {}건, 새로 저장 {}건, 요약 {}건", r.fetched(), r.saved(), r.summarized());
        } catch (RuntimeException e) {
            log.warn("뉴스 배치 실패, 다음 배치에서 다시 시도: {}", e.getMessage());
        }
    }

    boolean inActiveHours(LocalDateTime now) {
        DayOfWeek d = now.getDayOfWeek();
        return d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY
                && now.getHour() >= startHour && now.getHour() < endHour;
    }

    /** 수집 한 번 + 요약 한 번. 테스트에서 직접 부른다 */
    public BatchResult runOnce() {
        int fetched = 0;
        int saved = 0;
        if (finlight.hasKey()) {
            Map<String, String> stocks = stockNames();
            List<FinlightClient.Article> articles = finlight.latestKorean(List.copyOf(stocks.keySet()), pageSize);
            fetched = articles.size();
            saved = save(articles, new StockMatcher(stocks));
        } else {
            log.debug("FINLIGHT_API_KEY가 없어 뉴스 수집을 건너뜀");
        }
        int summarized = aiEnabled ? summarizePending() : 0;
        return new BatchResult(fetched, saved, summarized);
    }

    int save(List<FinlightClient.Article> articles, StockMatcher matcher) {
        int saved = 0;
        for (FinlightClient.Article a : articles) {
            if (a.link() == null || a.link().isBlank() || a.title() == null || a.title().isBlank()
                    || a.publishDate() == null) {
                continue;
            }
            Set<String> codes = matcher.match(a.title(), a.summary());
            if (codes.isEmpty()) {
                continue;
            }
            String newsId = newsId(a.link());
            LocalDateTime pubTs;
            try {
                pubTs = OffsetDateTime.parse(a.publishDate()).atZoneSameInstant(KST).toLocalDateTime();
            } catch (RuntimeException e) {
                continue;
            }
            String cont = a.summary() == null || a.summary().isBlank() ? a.title() : a.summary();
            Boolean inserted = tx.execute(s -> {
                int n = jdbc.update("""
                        INSERT INTO news (news_id, news_titl, news_cont, news_url, pub_ts, reg_ts)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (news_id) DO NOTHING
                        """, newsId, cut(a.title().strip(), 500), cont.strip(), cut(a.link(), 1000), pubTs,
                        LocalDateTime.now(clock));
                if (n == 0) {
                    return false;
                }
                for (String code : codes) {
                    jdbc.update("INSERT INTO news_stock (news_id, tck_cd, reg_ts) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                            newsId, code, LocalDateTime.now(clock));
                }
                return true;
            });
            if (Boolean.TRUE.equals(inserted)) {
                saved++;
            }
        }
        return saved;
    }

    /** 아직 요약이 없는 기사를 최신순으로 요약한다. 실패한 기사는 비워 두고 다음 배치에서 다시 */
    int summarizePending() {
        List<Map<String, Object>> pending = jdbc.queryForList("""
                SELECT news_id, news_titl, news_cont FROM news
                WHERE ai_smry_cont IS NULL ORDER BY pub_ts DESC LIMIT ?
                """, summarizePerBatch);
        int done = 0;
        for (Map<String, Object> n : pending) {
            String newsId = (String) n.get("news_id");
            try {
                AiServerClient.NewsSummary s = aiClient.summarize(newsId, (String) n.get("news_titl"),
                        (String) n.get("news_cont"));
                BigDecimal score = s.aiSntmScr().max(BigDecimal.ONE.negate()).min(BigDecimal.ONE)
                        .setScale(2, RoundingMode.HALF_UP);
                done += jdbc.update("UPDATE news SET ai_smry_cont = ?, ai_sntm_scr = ? WHERE news_id = ? AND ai_smry_cont IS NULL",
                        cut(s.aiSmryCont(), 1000), score, newsId);
            } catch (RuntimeException e) {
                log.info("뉴스 요약 실패, 다음 배치에서 다시 요청 newsId={}: {}", newsId, e.getMessage());
            }
        }
        return done;
    }

    private Map<String, String> stockNames() {
        Map<String, String> m = new LinkedHashMap<>();
        jdbc.query("SELECT tck_cd, tck_nm FROM stock_master ORDER BY tck_cd",
                rs -> {
                    m.put(rs.getString("tck_cd"), rs.getString("tck_nm"));
                });
        return m;
    }

    /** 원문 링크로 만든 기사 번호. 같은 기사는 몇 번 받아도 같은 번호라 중복 저장되지 않는다 */
    static String newsId(String link) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(link.strip().getBytes(StandardCharsets.UTF_8));
            return "FL-" + HexFormat.of().formatHex(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
