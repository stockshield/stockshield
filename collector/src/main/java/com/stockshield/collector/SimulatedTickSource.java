package com.stockshield.collector;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockshield.collector.CollectorProperties.Stock;

/**
 * 장 외 시간 시연용 시세 (MKT_TICK_001 #2).
 *
 * - REPLAY: 실시간 수집 때 남긴 data/ticks-YYYYMMDD.jsonl을 원래 간격대로(speed배) 다시 보낸다.
 *   시세 시각(prcTs)은 보내는 시점으로 바꾸고 원래 체결 시각은 orgTs에 담는다. 파일 끝에 닿으면 처음부터 다시 재생한다.
 * - MOCK: 재생할 파일이 없을 때 종목마다 1초에 한 번 무작위로 조금씩 움직이는 가짜 시세를 보낸다.
 * 두 방식 모두 실시간과 같은 market.tick 경로를 써서 화면·위험 의심 판단·주문이 그대로 동작한다.
 */
@Component
public class SimulatedTickSource {

    private static final Logger log = LoggerFactory.getLogger(SimulatedTickSource.class);
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final CollectorProperties props;
    private final TickPublisher publisher;
    private final ObjectMapper objectMapper;
    private volatile boolean running = true;

    public SimulatedTickSource(CollectorProperties props, TickPublisher publisher, ObjectMapper objectMapper) {
        this.props = props;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
    }

    public void stop() {
        running = false;
    }

    public void replayForever() {
        Optional<Path> file = replayFile();
        if (file.isEmpty()) {
            log.warn("재생할 시세 파일이 없어 가짜 시세(MOCK)로 보냅니다 (dir={})", props.record().dir());
            mockForever();
            return;
        }
        double speed = props.replay().speed() > 0 ? props.replay().speed() : 1.0;
        log.info("시세 재생: {} (x{})", file.get().toAbsolutePath(), speed);
        while (running) {
            replayOnce(file.get(), speed);
        }
    }

    private void replayOnce(Path file, double speed) {
        OffsetDateTime prev = null;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while (running && (line = r.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                TickMessage t = objectMapper.readValue(line, TickMessage.class);
                OffsetDateTime ts = OffsetDateTime.parse(t.orgTs() != null ? t.orgTs() : t.prcTs());
                if (prev != null && ts.isAfter(prev)) {
                    long gapMs = (long) (Duration.between(prev, ts).toMillis() / speed);
                    Thread.sleep(Math.min(gapMs, 10_000));   // 점심시간 같은 긴 공백은 10초로 줄인다
                }
                prev = ts;
                publisher.publish(t.replayedAt(nowTs()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        } catch (IOException e) {
            log.warn("시세 파일 재생 실패: {}", e.getMessage());
            sleep(5_000);
        }
    }

    public void mockForever() {
        Map<String, Long> prices = new HashMap<>();
        Map<String, Long> base = new HashMap<>();
        Map<String, Long> acc = new HashMap<>();
        for (Stock s : props.stocks()) {
            long p = seedPrice(s.code());
            prices.put(s.code(), p);
            base.put(s.code(), p);
            acc.put(s.code(), 0L);
        }
        log.info("가짜 시세(MOCK) 전송 시작: 종목 {}개, 1초 간격", prices.size());
        while (running) {
            for (Stock s : props.stocks()) {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                long prev = prices.get(s.code());
                long next = Math.max(100, Math.round(prev * (1 + rnd.nextGaussian() * 0.002)));
                prices.put(s.code(), next);
                long vol = 1 + rnd.nextInt(500);
                acc.merge(s.code(), vol, Long::sum);
                long chg = next - base.get(s.code());
                BigDecimal rate = BigDecimal.valueOf(chg * 100.0 / base.get(s.code())).setScale(2, RoundingMode.HALF_UP);
                publisher.publish(TickMessage.tick(s.code(), next, chg, rate, vol, acc.get(s.code()), nowTs(), "MOCK"));
            }
            sleep(1_000);
        }
    }

    /** 가짜 시세 시작가: 재생 파일이 있으면 마지막 가격, 없으면 종목코드로 정한 값(50,000 ~ 500,000원) */
    private long seedPrice(String code) {
        return 50_000L + (Math.abs(code.hashCode()) % 450) * 1_000L;
    }

    private Optional<Path> replayFile() {
        String configured = props.replay().file();
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured);
            return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
        }
        Path dir = Path.of(props.record().dir());
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().matches("ticks-\\d{8}\\.jsonl"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static String nowTs() {
        return OffsetDateTime.now(KST).truncatedTo(ChronoUnit.SECONDS).format(ISO);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
