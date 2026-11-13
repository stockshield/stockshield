package com.stockshield.fds.detection;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockshield.fds.api.CheckResult;
import com.stockshield.fds.api.RequestMeta;

/**
 * fds_detection을 쓰는 유일한 곳. 저장하거나 결과가 바뀌면 DetectionChangedEvent를 발행한다(FDS_LOG_001 #3, 경보 발행 주체 일원화).
 *
 * 규칙(연결 합의서 v0.0.8 4절, 테이블 정의서 '공통 규칙' 7행):
 * - 앱 계정은 rslt_cd, wdr_no, ai_*, chk_yn, mod_ts만 UPDATE할 수 있다. 엔티티를 읽어 고쳐 저장하지 않고,
 *   바뀌는 컬럼만 SET하는 쿼리로 바꾼다(더티 체킹이 다른 트랜잭션이 바꾼 값을 되돌리는 일을 막는다).
 * - PENDING 결과 변경은 WHERE rslt_cd = 'PENDING' 조건부, chk_yn 변경은 WHERE chk_yn = 'N' 조건부로 한 번만 반영한다.
 * - 결과·확인을 바꿀 때만 mod_ts를 Clock 시각으로 직접 넣는다(쿼리에는 JPA Auditing이 적용되지 않는다).
 * - 인증 대기는 reg_ts > 현재 − 5분일 때만 유효하고, reg_ts <= 현재 − 5분이면 만료다(5분 경계, 합의 9-2).
 *
 * 트랜잭션을 직접 열지 않는다. JdbcTemplate은 호출한 쪽의 트랜잭션(같은 DB 연결)에 참여한다.
 */
@Component
public class DetectionRecorder {

    /** 인증 대기 유효 시간: 로그인 인증 코드, 출금 428 뒤 재요청 (FDS_LGN_001 #2, FDS_WDR_001 #2, FDS_LOG_001 #5) */
    public static final Duration PENDING_TTL = Duration.ofMinutes(5);

    private static final String INSERT = """
            INSERT INTO fds_detection (rule_cd, mbr_no, acnt_no, ord_no, wdr_no, req_idmp_key,
                                       conn_ip, dvc_id, user_agt, rslt_cd, dtl_data, reg_ts, mod_ts)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
            RETURNING dtc_seq
            """;

    /** 같은 출금 요청(계좌·멱등키)의 인증 대기 기록을 잡는다. 계좌 락 다음에 잡으므로 순서는 계좌 → 탐지 행이다 */
    private static final String LOCK_PENDING_WITHDRAW = """
            SELECT dtc_seq, reg_ts, dtl_data ->> 'reqHash' AS req_hash
              FROM fds_detection
             WHERE acnt_no = ?
               AND req_idmp_key = ?
               AND rule_cd = 'WITHDRAW_LARGE'
               AND rslt_cd = 'PENDING'
             ORDER BY dtc_seq DESC
             LIMIT 1
               FOR NO KEY UPDATE
            """;

    private static final String CHANGE_PENDING = """
            UPDATE fds_detection
               SET rslt_cd = ?, wdr_no = COALESCE(CAST(? AS VARCHAR), wdr_no), mod_ts = ?
             WHERE dtc_seq = ?
               AND rslt_cd = 'PENDING'
            RETURNING rule_cd, acnt_no, reg_ts
            """;

    private static final String EXPIRE_PENDING = """
            UPDATE fds_detection
               SET rslt_cd = 'FAIL', mod_ts = ?
             WHERE rslt_cd = 'PENDING'
               AND reg_ts <= ?
            RETURNING dtc_seq, rule_cd, acnt_no, reg_ts
            """;

    private static final String MARK_CHECKED = """
            UPDATE fds_detection SET chk_yn = 'Y', mod_ts = ?
             WHERE dtc_seq = ? AND chk_yn = 'N'
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public DetectionRecorder(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                             ApplicationEventPublisher events, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.events = events;
        this.clock = clock;
    }

    /** 처음 저장하는 탐지 기록. 처음 저장한 사실(규칙·회원·계좌·주문·멱등키·IP·기기·상세)은 이후 바꾸지 않는다 */
    public record NewDetection(DetectionRule rule, long mbrNo, String acntNo, String ordNo, String wdrNo,
                               String reqIdmpKey, RequestMeta meta, DetectionResult result,
                               Map<String, Object> detail) {
    }

    public long insert(NewDetection d) {
        LocalDateTime now = now();
        Timestamp ts = Timestamp.valueOf(now);
        Long dtcSeq = jdbcTemplate.queryForObject(INSERT, Long.class,
                d.rule().name(), d.mbrNo(), d.acntNo(), d.ordNo(), d.wdrNo(), d.reqIdmpKey(),
                d.meta().connIp(), d.meta().dvcId(), d.meta().userAgt(), d.result().name(),
                toJson(d.detail()), ts, ts);
        if (dtcSeq == null) {
            throw new IllegalStateException("탐지 기록 번호를 받지 못했습니다");
        }
        events.publishEvent(new DetectionChangedEvent(dtcSeq, d.rule(), d.result(), d.acntNo(), now));
        return dtcSeq;
    }

    /** 같은 출금 요청의 인증 대기 기록을 FOR NO KEY UPDATE로 잡는다. 없으면 empty */
    public Optional<PendingDetection> lockPendingWithdraw(String acntNo, String idmpKey) {
        List<PendingDetection> rows = jdbcTemplate.query(LOCK_PENDING_WITHDRAW,
                (rs, n) -> new PendingDetection(rs.getLong("dtc_seq"),
                        rs.getObject("reg_ts", LocalDateTime.class), rs.getString("req_hash")),
                acntNo, idmpKey);
        return rows.stream().findFirst();
    }

    /**
     * 인증 대기 기록의 결과를 바꾼다. 아직 PENDING일 때만 바뀌고, 바뀌었으면 true.
     * wdrNo가 null이면 출금번호는 그대로 둔다.
     */
    public boolean changePendingResult(long dtcSeq, DetectionResult result, String wdrNo) {
        if (result == DetectionResult.PENDING) {
            throw new IllegalArgumentException("PENDING으로는 바꿀 수 없습니다");
        }
        LocalDateTime now = now();
        List<DetectionChangedEvent> changed = jdbcTemplate.query(CHANGE_PENDING,
                (rs, n) -> new DetectionChangedEvent(dtcSeq, DetectionRule.valueOf(rs.getString("rule_cd")), result,
                        rs.getString("acnt_no"), rs.getObject("reg_ts", LocalDateTime.class)),
                result.name(), wdrNo, Timestamp.valueOf(now), dtcSeq);
        changed.forEach(events::publishEvent);
        return !changed.isEmpty();
    }

    /** 5분이 지난 인증 대기 기록을 모두 FAIL로 확정하고, 확정한 건수를 돌려준다 (FDS_LOG_001 #5) */
    public int expirePending() {
        LocalDateTime now = now();
        List<DetectionChangedEvent> expired = jdbcTemplate.query(EXPIRE_PENDING,
                (rs, n) -> new DetectionChangedEvent(rs.getLong("dtc_seq"), DetectionRule.valueOf(rs.getString("rule_cd")),
                        DetectionResult.FAIL, rs.getString("acnt_no"), rs.getObject("reg_ts", LocalDateTime.class)),
                Timestamp.valueOf(now), Timestamp.valueOf(now.minus(PENDING_TTL)));
        expired.forEach(events::publishEvent);
        return expired.size();
    }

    /** 관리자 확인. 동시에 확인해도 한 번만 CHECKED가 된다 (합의서 8절) */
    public CheckResult markChecked(long dtcSeq) {
        int updated = jdbcTemplate.update(MARK_CHECKED, Timestamp.valueOf(now()), dtcSeq);
        if (updated == 1) {
            return CheckResult.CHECKED;
        }
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fds_detection WHERE dtc_seq = ?", Integer.class, dtcSeq);
        return exists != null && exists > 0 ? CheckResult.ALREADY_CHECKED : CheckResult.NOT_FOUND;
    }

    /** 인증 대기가 아직 유효한지: reg_ts > 현재 − 5분 (합의 9-2) */
    public boolean isPendingValid(LocalDateTime regTs) {
        return regTs.isAfter(now().minus(PENDING_TTL));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private String toJson(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("탐지 상세 내용을 JSON으로 바꾸지 못했습니다", e);
        }
    }
}
