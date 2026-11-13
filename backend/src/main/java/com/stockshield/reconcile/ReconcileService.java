package com.stockshield.reconcile;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.stockshield.common.error.ApiException;
import com.stockshield.common.error.ErrorCode;
import com.stockshield.common.web.KstTime;
import com.stockshield.common.web.PageBody;

/**
 * 원장 대사 실행과 결과 조회 (ADM_REC_001, API 명세서 'API_명세서' 28~31행).
 * 비교(읽기 전용 스냅샷)와 저장(별도 트랜잭션)을 나누므로 이 클래스에는 @Transactional을 붙이지 않음.
 */
@Service
public class ReconcileService {

    static final Duration STALE_AFTER = Duration.ofMinutes(30);
    static final String STALE_REASON = "시작 후 30분이 지나도 끝나지 않아 중단된 것으로 정리함";
    static final String GAVE_UP_REASON = "다른 대사 실행이 진행 중이라 1분 간격 10회 재시도 후에도 시작하지 못함";
    static final DateTimeFormatter RCCL_DT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int FAIL_RSN_MAX = 500;
    private static final Logger log = LoggerFactory.getLogger(ReconcileService.class);

    private final ReconcileRepository repository;
    private final ReconcileSnapshotReader snapshotReader;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ReconcileService(ReconcileRepository repository, ReconcileSnapshotReader snapshotReader,
                            ApplicationEventPublisher events, Clock clock) {
        this.repository = repository;
        this.snapshotReader = snapshotReader;
        this.events = events;
        this.clock = clock;
    }

    /** 관리자 수동 실행. 실행 중이면 409 */
    public Map<String, Object> runManual(long adminMbrNo) {
        ReconcileRun run = run(ReconcileRun.MANUAL, adminMbrNo)
                .orElseThrow(() -> new ApiException(ErrorCode.RECONCILE_RUNNING));
        return runBody(run);
    }

    /** @return 다른 실행이 RUNNING이라 시작하지 못하면 empty */
    Optional<ReconcileRun> run(String trgDvCd, Long procMbrNo) {
        LocalDateTime startedAt = now();
        cleanupStale(startedAt);
        Optional<Long> started = repository.startRunning(today(startedAt), trgDvCd, procMbrNo, startedAt);
        if (started.isEmpty()) {
            return Optional.empty();
        }
        long rcclRunNo = started.get();
        try {
            repository.saveDone(rcclRunNo, snapshotReader.read(), now());
        } catch (RuntimeException e) {
            log.error("원장 대사 실패 rcclRunNo={}", rcclRunNo, e);
            repository.markFailed(rcclRunNo, failReason(e), now());
        }
        ReconcileRun run = repository.findRun(rcclRunNo).orElseThrow();
        if (run.needsAlert()) {
            events.publishEvent(new ReconcileFinishedEvent(run));
        }
        return Optional.of(run);
    }

    /** 정기 실행이 끝내 시작하지 못함 (ADM_REC_001 #3) */
    ReconcileRun recordScheduledGaveUp() {
        LocalDateTime now = now();
        long rcclRunNo = repository.insertFailed(today(now), ReconcileRun.SCHEDULED, null, GAVE_UP_REASON, now);
        ReconcileRun run = repository.findRun(rcclRunNo).orElseThrow();
        events.publishEvent(new ReconcileFinishedEvent(run));
        return run;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void cleanupOnStartup() {
        cleanupStale(now());
    }

    // 경계: 시작 후 정확히 30분이면 정리 대상 (인증 대기 5분 경계와 같은 식, 문서 수정 대기 목록 #49)
    private void cleanupStale(LocalDateTime now) {
        int cleaned = repository.failStaleRunning(now.minus(STALE_AFTER), STALE_REASON, now);
        if (cleaned > 0) {
            log.warn("중단된 원장 대사 {}건을 FAILED로 정리함", cleaned);
        }
    }

    public PageBody history(int page, int size) {
        Pageable pageable = PageBody.request(page, size, Sort.unsorted());
        List<Map<String, Object>> content = repository.findRuns(pageable.getOffset(), pageable.getPageSize())
                .stream().map(ReconcileService::historyItem).toList();
        return PageBody.of(new PageImpl<>(content, pageable, repository.countRuns()), content);
    }

    public Map<String, Object> detail(long rcclRunNo, boolean diffOnly) {
        ReconcileRun run = repository.findRun(rcclRunNo)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        List<Map<String, Object>> results = repository.findResults(rcclRunNo, diffOnly).stream().map(item -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("acntNo", item.acntNo());
            r.put("chkItemCd", item.chkItemCd());
            r.put("tckCd", item.tckCd());
            r.put("expVal", item.expVal());
            r.put("actlVal", item.actlVal());
            r.put("diffYn", item.diffYn());
            return r;
        }).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rcclRunNo", run.rcclRunNo());
        body.put("rcclDt", run.rcclDt());
        body.put("runStatCd", run.runStatCd());
        body.put("results", results);
        return body;
    }

    /** 계좌번호는 넣지 않음 (ADM_REC_001 #4) */
    public Map<String, Object> scheduledStatus(String rcclDt) {
        Optional<ReconcileRun> run = repository.findLastScheduled(rcclDt);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rcclDt", rcclDt);
        body.put("rcclRunNo", run.map(ReconcileRun::rcclRunNo).orElse(null));
        body.put("runStatCd", run.map(ReconcileRun::runStatCd).orElse(null));
        body.put("totAcntCnt", run.map(ReconcileRun::totAcntCnt).orElse(null));
        body.put("diffAcntCnt", run.map(ReconcileRun::diffAcntCnt).orElse(null));
        return body;
    }

    public String today() {
        return today(now());
    }

    /** 수동 실행 응답과 관제 경보가 같은 형식 (API 28행, 문서 수정 대기 목록 #8) */
    static Map<String, Object> runBody(ReconcileRun run) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rcclRunNo", run.rcclRunNo());
        body.put("rcclDt", run.rcclDt());
        body.put("runStatCd", run.runStatCd());
        body.put("totAcntCnt", run.totAcntCnt());
        body.put("diffAcntCnt", run.diffAcntCnt());
        body.put("failRsnCont", run.failRsnCont());
        return body;
    }

    private static Map<String, Object> historyItem(ReconcileRun run) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("rcclRunNo", run.rcclRunNo());
        item.put("rcclDt", run.rcclDt());
        item.put("trgDvCd", run.trgDvCd());
        item.put("runStatCd", run.runStatCd());
        item.put("totAcntCnt", run.totAcntCnt());
        item.put("diffAcntCnt", run.diffAcntCnt());
        item.put("failRsnCont", run.failRsnCont());
        item.put("regTs", KstTime.format(run.regTs()));
        item.put("endTs", KstTime.format(run.endTs()));
        return item;
    }

    // 예외 메시지에는 계좌번호 같은 값이 섞일 수 있어 종류만 남김. 자세한 내용은 로그(거래추적번호)로 찾음
    private static String failReason(RuntimeException e) {
        String reason = "점검 중 오류: " + e.getClass().getSimpleName();
        return reason.length() > FAIL_RSN_MAX ? reason.substring(0, FAIL_RSN_MAX) : reason;
    }

    private static String today(LocalDateTime now) {
        return now.toLocalDate().format(RCCL_DT);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
