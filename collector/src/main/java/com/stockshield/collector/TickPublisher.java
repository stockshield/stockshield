package com.stockshield.collector;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

/**
 * 시세 한 건을 Kafka market.tick으로 보낸다(키: 종목·지수 코드, 같은 종목의 순서 보장).
 * 실시간(KIS) 시세는 날짜별 JSON Lines 파일(data/ticks-YYYYMMDD.jsonl)에도 남겨 장 외 시간 재생에 쓴다.
 * Kafka 응답은 기다리지 않는다. 보내지 못한 건은 로그만 남긴다(다음 체결이 곧 다시 온다).
 */
@Component
public class TickPublisher {

    private static final Logger log = LoggerFactory.getLogger(TickPublisher.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final CollectorProperties props;
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    private BufferedWriter writer;
    private LocalDate writerDate;

    public TickPublisher(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper, CollectorProperties props) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    public void publish(TickMessage msg) {
        String json;
        try {
            json = objectMapper.writeValueAsString(msg);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("시세 메시지를 JSON으로 바꾸지 못했습니다", e);
        }
        kafka.send(props.topic(), msg.code(), json).whenComplete((r, ex) -> {
            if (ex == null) {
                sent.incrementAndGet();
            } else if (failed.incrementAndGet() % 100 == 1) {
                log.warn("Kafka 전송 실패 (누적 {}건) code={}: {}", failed.get(), msg.code(), ex.getMessage());
            }
        });
        if ("KIS".equals(msg.src()) && props.record().enabled()) {
            record(json);
        }
    }

    private synchronized void record(String json) {
        try {
            LocalDate today = LocalDate.now(KST);
            if (writer == null || !today.equals(writerDate)) {
                closeWriter();
                Path dir = Path.of(props.record().dir());
                Files.createDirectories(dir);
                Path file = dir.resolve("ticks-" + today.toString().replace("-", "") + ".jsonl");
                writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                writerDate = today;
                log.info("실시간 시세를 {}에 기록합니다", file.toAbsolutePath());
            }
            writer.write(json);
            writer.newLine();
        } catch (IOException e) {
            log.warn("시세 기록 실패: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 5_000)
    public synchronized void flushAndReport() {
        try {
            if (writer != null) {
                writer.flush();
            }
        } catch (IOException e) {
            log.warn("시세 기록 flush 실패: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void report() {
        log.info("market.tick 전송 누적 {}건, 실패 {}건", sent.get(), failed.get());
    }

    @PreDestroy
    public synchronized void closeWriter() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // 종료 중
            }
            writer = null;
        }
    }

    long sentCount() {
        return sent.get();
    }
}
