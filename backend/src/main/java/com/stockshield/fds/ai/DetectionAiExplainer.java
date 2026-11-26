package com.stockshield.fds.ai;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockshield.common.error.ApiException;
import com.stockshield.common.error.ErrorCode;
import com.stockshield.fds.ai.AiServerClient.DetectionReason;
import com.stockshield.fds.config.FdsExecutorConfig;
import com.stockshield.fds.detection.DetectionAlertListener;
import com.stockshield.fds.detection.DetectionChangedEvent;
import com.stockshield.fds.detection.DetectionResult;

/**
 * 탐지 사유 AI 설명 (FDS_AI_001). 판단은 규칙이 이미 했고, AI는 관리자가 읽기 쉽게 설명만 한다.
 *
 * - 언제: 탐지 기록의 결과가 확정될 때 한 번. 처음부터 확정(PASS·FAIL·LOCKED·LOGGED)이면 저장 직후,
 *   인증 대기(PENDING)면 인증 성공·실패·만료로 바뀐 직후다. 대기 중에는 ai_stat_cd가 REQUESTED(요청 중)로 남는다.
 * - 커밋 뒤에 fdsExecutor에서 부른다. 요청 스레드는 기다리지 않는다(합의서 2절 규칙 5).
 * - 보내는 것: 규칙·결과 코드와 상세(dtl_data)뿐이다. 회원번호·아이디·계좌번호·IP·기기 정보·요청 해시는 빼고 보낸다(#3).
 * - 결과: 성공이면 ai_rsn_cont·ai_chk_cont와 DONE, 10초 안에 답이 없거나 실패하면 FAILED(#4). 둘 다 ai_stat_cd가
 *   REQUESTED일 때만 바꾸고, mod_ts는 바꾸지 않는다(합의서 8절). 바뀌면 관제 화면에 다시 알린다.
 * - stockshield.ai.enabled=false(테스트 전용)면 부르지 않고 REQUESTED로 둔다.
 */
@Component
public class DetectionAiExplainer {

    private static final Logger log = LoggerFactory.getLogger(DetectionAiExplainer.class);
    static final int MAX_LEN = 1000;

    /** 표준 용어 기준 개인정보 키. 대소문자와 _ 를 무시하고 비교한다 (AI 서버 prompts.py와 같은 목록) */
    static final Set<String> SENSITIVE_KEYS = Set.of(
            "mbrno", "lgnid", "mbrnm", "emladdr", "acntno", "rcvacntno", "rcvbanknm",
            "connip", "ip", "dvcid", "useragt", "useragent", "reqhash", "wdrpwd", "lgnpwd",
            "ordno", "wdrno", "idmpkey", "reqidmpkey");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AiServerClient client;
    private final DetectionAlertListener alerts;
    private final Executor executor;
    private final boolean enabled;

    public DetectionAiExplainer(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, AiServerClient client,
                                DetectionAlertListener alerts,
                                @Qualifier(FdsExecutorConfig.FDS_EXECUTOR) Executor executor,
                                @Value("${stockshield.ai.enabled:true}") boolean enabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.client = client;
        this.alerts = alerts;
        this.executor = executor;
        this.enabled = enabled;
    }

    /** 결과가 확정된 탐지 기록만 요청한다. 트랜잭션 밖(만료 정리 작업 등)에서 발행돼도 바로 처리한다 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDetectionChanged(DetectionChangedEvent event) {
        if (event.result() != DetectionResult.PENDING) {
            submit(event.dtcSeq());
        }
    }

    /**
     * 관리자 재요청 (API 명세서 'API_명세서' 26행). FAILED일 때만 REQUESTED로 바꾸고 다시 부른다.
     * 없으면 404 DETECTION_NOT_FOUND, FAILED가 아니면 409 AI_RETRY_NOT_ALLOWED.
     */
    public void retry(long dtcSeq) {
        int updated = jdbcTemplate.update(
                "UPDATE fds_detection SET ai_stat_cd = 'REQUESTED' WHERE dtc_seq = ? AND ai_stat_cd = 'FAILED'", dtcSeq);
        if (updated == 0) {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM fds_detection WHERE dtc_seq = ?", Integer.class, dtcSeq);
            throw new ApiException(exists != null && exists > 0 ? ErrorCode.AI_RETRY_NOT_ALLOWED
                    : ErrorCode.DETECTION_NOT_FOUND, Map.of("dtcSeq", dtcSeq));
        }
        alerts.resend(dtcSeq);
        submit(dtcSeq);
    }

    private void submit(long dtcSeq) {
        if (!enabled) {
            return;
        }
        try {
            executor.execute(() -> explain(dtcSeq));
        } catch (TaskRejectedException e) {
            log.warn("AI 설명 요청이 밀려 실패로 저장 dtcSeq={}", dtcSeq);
            finish(dtcSeq, null);
        }
    }

    /** AI 서버를 불러 결과를 저장한다. 이미 REQUESTED가 아니면(다른 요청이 끝냄) 부르지 않는다 */
    void explain(long dtcSeq) {
        List<Target> rows = jdbcTemplate.query("""
                        SELECT rule_cd, rslt_cd, dtl_data::text AS dtl FROM fds_detection
                         WHERE dtc_seq = ? AND ai_stat_cd = 'REQUESTED'
                        """,
                (rs, n) -> new Target(rs.getString("rule_cd"), rs.getString("rslt_cd"), rs.getString("dtl")), dtcSeq);
        if (rows.isEmpty()) {
            return;
        }
        Target t = rows.get(0);
        DetectionReason reason = null;
        try {
            reason = client.explain(dtcSeq, t.ruleCd(), t.rsltCd(), facts(t.dtl()));
        } catch (RuntimeException e) {
            log.warn("AI 설명 실패 dtcSeq={}: {} {}", dtcSeq, e.getClass().getSimpleName(), e.getMessage());
        }
        finish(dtcSeq, reason);
    }

    private void finish(long dtcSeq, DetectionReason reason) {
        int updated = reason == null
                ? jdbcTemplate.update("""
                        UPDATE fds_detection SET ai_stat_cd = 'FAILED'
                         WHERE dtc_seq = ? AND ai_stat_cd = 'REQUESTED'
                        """, dtcSeq)
                : jdbcTemplate.update("""
                        UPDATE fds_detection SET ai_rsn_cont = ?, ai_chk_cont = ?, ai_stat_cd = 'DONE'
                         WHERE dtc_seq = ? AND ai_stat_cd = 'REQUESTED'
                        """, limit(reason.aiRsnCont()), limit(reason.aiChkCont()), dtcSeq);
        if (updated == 1) {
            alerts.resend(dtcSeq);
        }
    }

    /** dtl_data에서 개인정보 키를 뺀 사실들 (중첩 포함) */
    Map<String, Object> facts(String dtlJson) {
        try {
            Map<String, Object> raw = objectMapper.readValue(dtlJson == null ? "{}" : dtlJson,
                    new TypeReference<Map<String, Object>>() { });
            return scrub(raw);
        } catch (IOException e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> scrub(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        in.forEach((k, v) -> {
            if (SENSITIVE_KEYS.contains(k.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""))) {
                return;
            }
            out.put(k, v instanceof Map<?, ?> m ? scrub((Map<String, Object>) m) : v);
        });
        return out;
    }

    private static String limit(String s) {
        return s == null || s.length() <= MAX_LEN ? s : s.substring(0, MAX_LEN);
    }

    private record Target(String ruleCd, String rsltCd, String dtl) {
    }
}
