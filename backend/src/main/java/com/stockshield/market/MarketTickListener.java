package com.stockshield.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockshield.fds.risk.StockRiskJudge;

/**
 * 시세 컨슈머 (MKT_TICK_002). Kafka market.tick을 컨슈머 그룹 market-consumer로 읽어
 * Redis 최신 시세(price:·index:)를 덮어쓰고 분봉을 저장한다.
 *
 * - Redis를 먼저 쓴다. 분봉 저장(DB)이 실패해도 주문이 읽는 최신 시세는 갱신된다.
 * - 해석할 수 없는 메시지는 로그만 남기고 건너뛴다(재시도해도 같으므로).
 * - 리스너 컨테이너는 Spring Boot 기본 팩토리로 만든다. 테스트에서는 spring.kafka.listener.auto-startup=false로 끈다
 *   (연결 합의서 12절). 토픽은 docker-compose의 kafka-init이 만들고 NewTopic 빈은 두지 않는다.
 */
@Component
public class MarketTickListener {

    public static final String TOPIC = "market.tick";
    public static final String GROUP = "market-consumer";

    private static final Logger log = LoggerFactory.getLogger(MarketTickListener.class);

    private final ObjectMapper objectMapper;
    private final PriceCache priceCache;
    private final MinuteBarWriter minuteBars;
    private final StockRiskJudge riskJudge;
    private final MarketBroadcaster broadcaster;

    public MarketTickListener(ObjectMapper objectMapper, PriceCache priceCache, MinuteBarWriter minuteBars,
                              StockRiskJudge riskJudge, MarketBroadcaster broadcaster) {
        this.objectMapper = objectMapper;
        this.priceCache = priceCache;
        this.minuteBars = minuteBars;
        this.riskJudge = riskJudge;
        this.broadcaster = broadcaster;
    }

    @KafkaListener(topics = TOPIC, groupId = GROUP)
    public void onMessage(String payload) {
        MarketTick tick;
        try {
            tick = objectMapper.readValue(payload, MarketTick.class);
        } catch (JsonProcessingException e) {
            log.warn("market.tick 메시지를 해석하지 못해 건너뜀: {}", e.getOriginalMessage());
            return;
        }
        handle(tick);
    }

    /** 메시지 한 건 처리. 테스트에서 직접 부른다 */
    public void handle(MarketTick tick) {
        if (tick.code() == null || tick.prcTs() == null) {
            log.warn("코드나 시세 시각이 없는 market.tick을 건너뜀: {}", tick);
            return;
        }
        if (tick.isTick() && tick.currentPrice() != null && tick.currentPrice() > 0) {
            priceCache.putPrice(tick);
            minuteBars.add(tick);
            try {
                riskJudge.onTick(tick.code(), tick.currentPrice(), tick.chgRt(), tick.prcTs());
            } catch (RuntimeException e) {
                log.warn("위험 의심 판단 실패 tckCd={}: {}", tick.code(), e.getMessage());
            }
            broadcaster.onTick(tick);
        } else if (tick.isIndex() && tick.idxVal() != null) {
            priceCache.putIndex(tick);
        } else {
            log.debug("처리하지 않는 market.tick: {}", tick);
        }
    }
}
