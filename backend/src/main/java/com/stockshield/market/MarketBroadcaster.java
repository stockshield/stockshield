package com.stockshield.market;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.stockshield.fds.risk.RiskStocksChanged;
import com.stockshield.fds.risk.StockRiskJudge;

/**
 * 시세를 화면으로 보낸다 (MKT_VIEW_001, SYS_CORE_002 구현방안).
 *
 * 형식은 연결 합의서 v0.0.9 회신 8번을 따른다.
 * - /topic/price/{종목코드}: 체결마다 {tckCd, currentPrice, chgPrc, chgRt, totalTrdVol, prcTs}
 * - /topic/ranking: 2초마다 GET /market/summary 응답 전체
 * - /topic/risk: 위험 의심 종목이 생기거나 풀릴 때 GET /market/risk-stocks 응답 전체
 * - /topic/admin/market: 10초마다 시세 지연(가장 최근 시세 시각이 60초 넘게 지남)을 판단하고, 지연 시작·해제 때
 *   {tckCd, tckNm, priceDelayed, prcTs}를 보낸다. 지연은 전체 시세 기준이라 tckCd·tckNm은 null이다
 * WebSocket 브로커가 없으면(테스트 등) 보내지 않는다. 전송 오류는 시세 처리를 멈추지 않는다.
 */
@Component
public class MarketBroadcaster {

    public static final String RANKING_TOPIC = "/topic/ranking";
    public static final String ADMIN_MARKET_TOPIC = "/topic/admin/market";
    public static final String RISK_TOPIC = "/topic/risk";

    private static final Logger log = LoggerFactory.getLogger(MarketBroadcaster.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'+09:00'");

    private final ObjectProvider<SimpMessageSendingOperations> messaging;
    private final StockRiskJudge riskJudge;
    private final MarketQueryService queryService;
    private final Clock clock;
    private final Map<String, MarketTick> latest = new ConcurrentHashMap<>();
    private final AtomicReference<Boolean> delayed = new AtomicReference<>();

    public MarketBroadcaster(ObjectProvider<SimpMessageSendingOperations> messaging, StockRiskJudge riskJudge,
                             MarketQueryService queryService, Clock clock) {
        this.messaging = messaging;
        this.riskJudge = riskJudge;
        this.queryService = queryService;
        this.clock = clock;
    }

    public void onTick(MarketTick t) {
        latest.put(t.code(), t);
        send("/topic/price/" + t.code(), priceItem(t));
    }

    @Scheduled(fixedDelay = 2_000, initialDelay = 2_000)
    public void publishRanking() {
        if (!latest.isEmpty() && messaging.getIfAvailable() != null) {
            try {
                send(RANKING_TOPIC, queryService.summary());
            } catch (RuntimeException e) {
                log.debug("순위 조회 실패: {}", e.getMessage());
            }
        }
    }

    @EventListener
    public void onRiskChanged(RiskStocksChanged event) {
        if (messaging.getIfAvailable() == null) {
            return;
        }
        try {
            send(RISK_TOPIC, queryService.riskStocks());
        } catch (RuntimeException e) {
            log.debug("위험 의심 종목 조회 실패: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    public void checkDelay() {
        riskJudge.sweep();
        boolean now = isPriceDelayed();
        Boolean before = delayed.getAndSet(now);
        if (before == null || before != now) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("tckCd", null);
            p.put("tckNm", null);
            p.put("priceDelayed", now);
            p.put("prcTs", riskJudge.latestPrcTs().map(ts -> ts.format(ISO)).orElse(null));
            send(ADMIN_MARKET_TOPIC, p);
            if (now) {
                log.warn("시세가 {}초 넘게 갱신되지 않았습니다 (마지막 시세 {})",
                        StockRiskJudge.STALE_AFTER.toSeconds(), p.get("prcTs"));
            } else if (before != null) {
                log.info("시세 갱신이 다시 시작되었습니다");
            }
        }
    }

    /** 가장 최근 시세 시각이 60초 넘게 지났거나 시세가 아직 없으면 지연 (SYS_CORE_002 #2, API 'API_명세서' 12행 priceDelayed) */
    public boolean isPriceDelayed() {
        Optional<LocalDateTime> last = riskJudge.latestPrcTs();
        return last.isEmpty() || !last.get().isAfter(LocalDateTime.now(clock).minus(StockRiskJudge.STALE_AFTER));
    }

    private static Map<String, Object> priceItem(MarketTick t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tckCd", t.code());
        m.put("currentPrice", t.currentPrice());
        m.put("chgPrc", t.chgPrc());
        m.put("chgRt", t.chgRt());
        m.put("totalTrdVol", t.accVol());
        m.put("prcTs", t.prcTs());
        return m;
    }

    private void send(String destination, Object payload) {
        SimpMessageSendingOperations ops = messaging.getIfAvailable();
        if (ops == null) {
            return;
        }
        try {
            ops.convertAndSend(destination, payload);
        } catch (RuntimeException e) {
            log.debug("{} 전송 실패: {}", destination, e.getMessage());
        }
    }
}
