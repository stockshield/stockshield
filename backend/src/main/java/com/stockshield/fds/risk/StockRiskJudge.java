package com.stockshield.fds.risk;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockshield.fds.risk.ReturnWindow.MinuteReturn;

/**
 * FDS ④ 위험 의심 종목 판단 (FDS_MKT_001). 시세 컨슈머가 체결마다 넘겨주고, 분이 바뀔 때마다 판단한다.
 *
 * - 1분 수익률의 Z-Score가 3 이상(표본 30개 이상, 최근 30분 기준)이거나 전일 대비 등락률이 ±10% 이상이면 위험 의심이다.
 * - 위험 의심이면 Redis risk:stock:{종목코드}(TTL 300초)를 쓰고 RiskStocksChanged를 발행한다(화면 전송은 MarketBroadcaster). 조건이 이어지면 다시 써서
 *   마지막으로 맞은 뒤 5분 동안 유지된다(FDS_MKT_001 #3).
 * - 시세가 60초 넘게 갱신되지 않으면 오래된 시세로 잘못 표시하지 않도록 표시를 내린다(#4). 5분이 지나 사라진 표시도
 *   화면이 알 수 있게 riskYn=N으로 알린다(sweep, 10초마다).
 * - 수익률 통계는 Redis stat:ret:{종목코드} 해시에도 남긴다(MKT_TICK_002 #2). 통계의 원본은 메모리라 서버를 다시 켜면
 *   30분 동안은 Z-Score 조건 없이 등락률 조건만 판단한다.
 * 판단은 시세 데이터로만 하고 주문을 막지 않는다. Redis 오류는 로그만 남기고 시세 처리를 멈추지 않는다.
 */
@Component
public class StockRiskJudge {

    public static final String RISK_KEY = "risk:stock:";
    public static final String STAT_KEY = "stat:ret:";
    public static final Duration RISK_TTL = Duration.ofMinutes(5);
    public static final Duration STALE_AFTER = Duration.ofSeconds(60);
    static final double Z_LIMIT = 3.0;
    static final BigDecimal RATE_LIMIT = BigDecimal.TEN;

    private static final Logger log = LoggerFactory.getLogger(StockRiskJudge.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter BS_MI = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'+09:00'");

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    private final Map<String, ReturnWindow> windows = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> lastPrcTs = new ConcurrentHashMap<>();
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public StockRiskJudge(StringRedisTemplate redis, ObjectMapper objectMapper,
                          ApplicationEventPublisher events, Clock clock) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.events = events;
        this.clock = clock;
    }

    /** 판단 결과: 위험 의심이면 방향(UP·DOWN)과 사유 */
    public record Risk(String tckCd, String dir, BigDecimal chgRt, String reason, String dtcTs) {
    }

    /** 체결 하나. 분이 바뀌어 판단했으면 그 결과(위험 의심일 때만)를 돌려준다 */
    public Optional<Risk> onTick(String tckCd, long price, BigDecimal chgRt, String prcTs) {
        LocalDateTime ts = OffsetDateTime.parse(prcTs).atZoneSameInstant(KST).toLocalDateTime();
        lastPrcTs.merge(tckCd, ts, (a, b) -> b.isAfter(a) ? b : a);
        ReturnWindow window = windows.computeIfAbsent(tckCd, k -> new ReturnWindow());
        Optional<MinuteReturn> done = window.add(ts.format(BS_MI), price);
        if (done.isEmpty()) {
            return Optional.empty();
        }
        MinuteReturn m = done.get();
        writeStats(tckCd, m);
        Optional<Risk> risk = judge(tckCd, m, chgRt, ts);
        risk.ifPresent(this::mark);
        return risk;
    }

    static Optional<Risk> judge(String tckCd, MinuteReturn m, BigDecimal chgRt, LocalDateTime ts) {
        Optional<Double> z = m.z();
        boolean zHit = z.isPresent() && Math.abs(z.get()) >= Z_LIMIT;
        boolean rateHit = chgRt != null && chgRt.abs().compareTo(RATE_LIMIT) >= 0;
        if (!zHit && !rateHit) {
            return Optional.empty();
        }
        String dir;
        if (zHit) {
            dir = m.ret() >= 0 ? "UP" : "DOWN";
        } else {
            dir = chgRt.signum() >= 0 ? "UP" : "DOWN";
        }
        StringBuilder reason = new StringBuilder();
        if (zHit) {
            reason.append(String.format(Locale.ROOT, "1분 수익률 %+.2f%% (Z=%.1f)", m.ret() * 100, z.get()));
        }
        if (rateHit) {
            if (reason.length() > 0) {
                reason.append(", ");
            }
            reason.append(String.format(Locale.ROOT, "전일 대비 %+.2f%%", chgRt));
        }
        return Optional.of(new Risk(tckCd, dir, chgRt, reason.toString(), ts.format(ISO)));
    }

    /** 10초마다: 시세가 60초 넘게 끊긴 종목과 5분이 지나 사라진 표시를 내리고 riskYn=N으로 알린다 */
    public void sweep() {
        LocalDateTime staleBefore = LocalDateTime.now(clock).minus(STALE_AFTER);
        for (String tckCd : active) {
            LocalDateTime last = lastPrcTs.get(tckCd);
            boolean stale = last == null || !last.isAfter(staleBefore);
            try {
                if (stale) {
                    redis.delete(RISK_KEY + tckCd);
                } else if (Boolean.TRUE.equals(redis.hasKey(RISK_KEY + tckCd))) {
                    continue;
                }
                active.remove(tckCd);
                publish(tckCd, false);
            } catch (RuntimeException e) {
                log.warn("위험 의심 표시 정리 실패 tckCd={}: {}", tckCd, e.getMessage());
            }
        }
    }

    /** 가장 최근 시세 시각. 시세 지연 판단(관제 경고·summary.priceDelayed)에 쓴다 */
    public Optional<LocalDateTime> latestPrcTs() {
        return lastPrcTs.values().stream().max(LocalDateTime::compareTo);
    }

    public boolean isActive(String tckCd) {
        return active.contains(tckCd);
    }

    private void mark(Risk r) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("tckCd", r.tckCd());
        v.put("riskYn", "Y");
        v.put("dir", r.dir());
        v.put("chgRt", r.chgRt());
        v.put("reason", r.reason());
        v.put("dtcTs", r.dtcTs());
        try {
            redis.opsForValue().set(RISK_KEY + r.tckCd(), json(v), RISK_TTL);
            active.add(r.tckCd());
            publish(r.tckCd(), true);
        } catch (RuntimeException e) {
            log.warn("위험 의심 종목 저장 실패 tckCd={}: {}", r.tckCd(), e.getMessage());
        }
    }

    private void writeStats(String tckCd, MinuteReturn m) {
        try {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("bsMi", m.minute());
            h.put("lastRet", Double.toString(m.ret()));
            h.put("n", Integer.toString(m.n()));
            h.put("mean", Double.toString(m.mean()));
            h.put("std", Double.toString(m.std()));
            m.z().ifPresent(z -> h.put("z", Double.toString(z)));
            redis.opsForHash().putAll(STAT_KEY + tckCd, h);
            redis.expire(STAT_KEY + tckCd, Duration.ofDays(1));
        } catch (RuntimeException e) {
            log.debug("수익률 통계 저장 실패 tckCd={}: {}", tckCd, e.getMessage());
        }
    }

    private void publish(String tckCd, boolean riskYn) {
        try {
            events.publishEvent(new RiskStocksChanged(tckCd, riskYn));
        } catch (RuntimeException e) {
            log.debug("위험 의심 변경 알림 실패: {}", e.getMessage());
        }
    }

    private String json(Map<String, Object> v) {
        try {
            return objectMapper.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
