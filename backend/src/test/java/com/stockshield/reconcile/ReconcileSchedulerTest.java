package com.stockshield.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 정기 대사·재시도와 내부 결과 확인 API (ADM_REC_001 #3·#4, 요구사항명세서 부록 #9·#19, API 명세서 'API_명세서' 31행).
 * 스케줄링은 테스트에서 꺼져 있어(연결 합의서 12절) 고정 Clock으로 스케줄 메서드를 직접 부름.
 * 날짜가 겹치면 그날 마지막 정기 실행이 다른 테스트 것이 될 수 있어 테스트마다 다른 날짜를 씀.
 */
class ReconcileSchedulerTest extends ReconcileTestSupport {

    @Autowired ReconcileScheduler scheduler;
    @Autowired ReconcileRepository repository;

    private MockHttpServletRequestBuilder statusRequest(String rcclDt) {
        MockHttpServletRequestBuilder b = get("/api/v1/internal/reconcile/status")
                .header("X-Internal-Token", TEST_INTERNAL_TOKEN);
        return rcclDt == null ? b : b.param("rcclDt", rcclDt);
    }

    @Test
    @DisplayName("16:00 정기 실행은 SCHEDULED로 남고, 내부 API가 그날 마지막 정기 실행의 번호·상태·건수를 돌려준다")
    void scheduledRunIsReportedByInternalApi() throws Exception {
        testClock.set(LocalDateTime.of(2030, 1, 9, 16, 0));

        assertThat(scheduler.attempt(0)).isEqualTo(ReconcileScheduler.Outcome.RAN);

        ReconcileRun run = repository.findLastScheduled("20300109").orElseThrow();
        assertThat(run.runStatCd()).isEqualTo("DONE");
        assertThat(run.procMbrNo()).isNull();
        mockMvc.perform(statusRequest(null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rcclDt").value("20300109"))
                .andExpect(jsonPath("$.rcclRunNo").value(run.rcclRunNo()))
                .andExpect(jsonPath("$.runStatCd").value("DONE"))
                .andExpect(jsonPath("$.totAcntCnt").value(run.totAcntCnt()))
                .andExpect(jsonPath("$.diffAcntCnt").value(run.diffAcntCnt()))
                .andExpect(jsonPath("$.acntNo").doesNotExist());
    }

    @Test
    @DisplayName("다른 실행이 진행 중이면 1분 뒤 재시도를 10번까지 예약하고, 11번째에도 못 하면 SCHEDULED·FAILED를 사유와 함께 남긴다")
    void scheduledRunGivesUpAfterTenRetries() throws Exception {
        LocalDateTime sixteen = LocalDateTime.of(2030, 1, 10, 16, 0);
        testClock.set(sixteen);
        insertRunning(sixteen.minusMinutes(1));

        for (int retryNo = 0; retryNo < ReconcileScheduler.MAX_RETRIES; retryNo++) {
            testClock.set(sixteen.plusMinutes(retryNo));
            assertThat(scheduler.attempt(retryNo)).isEqualTo(ReconcileScheduler.Outcome.RETRY_SCHEDULED);
            assertThat(repository.findLastScheduled("20300110")).isEmpty();
        }
        testClock.set(sixteen.plusMinutes(ReconcileScheduler.MAX_RETRIES));
        assertThat(scheduler.attempt(ReconcileScheduler.MAX_RETRIES)).isEqualTo(ReconcileScheduler.Outcome.GAVE_UP);

        ReconcileRun failed = repository.findLastScheduled("20300110").orElseThrow();
        assertThat(failed.runStatCd()).isEqualTo("FAILED");
        assertThat(failed.failRsnCont()).isEqualTo(ReconcileService.GAVE_UP_REASON);
        assertThat(failed.endTs()).isEqualTo(sixteen.plusMinutes(10));
        mockMvc.perform(statusRequest("20300110"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runStatCd").value("FAILED"))
                .andExpect(jsonPath("$.totAcntCnt").isEmpty())
                .andExpect(jsonPath("$.diffAcntCnt").isEmpty());
    }

    @Test
    @DisplayName("동시에 시작해도 RUNNING은 1건만 생긴다(부분 UNIQUE + ON CONFLICT)")
    void onlyOneRunCanStart() throws Exception {
        LocalDateTime now = LocalDateTime.of(2030, 1, 11, 16, 0);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Callable<Optional<Long>>> calls = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                calls.add(() -> repository.startRunning("20300111", ReconcileRun.SCHEDULED, null, now));
            }
            List<Optional<Long>> started = new ArrayList<>();
            for (Future<Optional<Long>> f : pool.invokeAll(calls)) {
                started.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(started.stream().filter(Optional::isPresent).count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("내부 API: 토큰이 없거나 틀리면 401, 날짜 형식이 틀리면 400, 그날 정기 실행이 없으면 값이 모두 null")
    void internalApiChecksTokenAndDate() throws Exception {
        mockMvc.perform(get("/api/v1/internal/reconcile/status").param("rcclDt", "20300109"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/v1/internal/reconcile/status").header("X-Internal-Token", "wrong"))
                .andExpect(status().isUnauthorized());
        // 토큰이 틀리면 날짜 형식을 보기 전에 401
        mockMvc.perform(get("/api/v1/internal/reconcile/status").param("rcclDt", "bad"))
                .andExpect(status().isUnauthorized());

        for (String bad : List.of("2030-01-09", "20301332", "20300230", "2030019", "")) {
            mockMvc.perform(statusRequest(bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }

        mockMvc.perform(statusRequest("20000103"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rcclDt").value("20000103"))
                .andExpect(jsonPath("$.rcclRunNo").isEmpty())
                .andExpect(jsonPath("$.runStatCd").isEmpty())
                .andExpect(jsonPath("$.totAcntCnt").isEmpty())
                .andExpect(jsonPath("$.diffAcntCnt").isEmpty());
    }
}
