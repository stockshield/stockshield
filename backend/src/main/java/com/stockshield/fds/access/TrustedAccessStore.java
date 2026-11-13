package com.stockshield.fds.access;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.stockshield.fds.api.RequestMeta;

/**
 * trusted_access(신뢰 접속 정보)를 읽고 쓰는 유일한 곳 (합의서 4절: 쓰는 쪽은 윤제정만).
 *
 * - reg_ts·last_use_ts는 언제나 Clock 시각을 파라미터로 직접 넣는다. 쿼리에는 JPA Auditing이 적용되지 않아,
 *   넣지 않으면 DB 기본값이 들어가 FDS ③의 '인증 후 24시간' 판단이 고정 시계 테스트와 어긋난다(합의 8-2).
 * - 등록은 INSERT … ON CONFLICT (mbr_no, conn_ip, dvc_id) DO UPDATE 한 문장으로 해서
 *   같은 기기로 동시에 확인해도 UK 위반이 나지 않게 한다(합의 9-2).
 * 트랜잭션을 직접 열지 않는다. 호출한 쪽(FdsService 메서드의 전파 선언)의 트랜잭션에 참여한다.
 */
@Component
public class TrustedAccessStore {

    /** 등록 경로 (테이블 정의서 '코드 정의' 10·11행) */
    public enum RegPath { SIGNUP, EMAIL_AUTH }

    private static final String KNOWN_IP =
            "SELECT EXISTS (SELECT 1 FROM trusted_access WHERE mbr_no = ? AND conn_ip = ?)";
    private static final String KNOWN_DEVICE =
            "SELECT EXISTS (SELECT 1 FROM trusted_access WHERE mbr_no = ? AND dvc_id = ?)";

    /** (IP, 기기)가 같은 행이 있을 때만 그 행의 마지막 사용 시각을 바꾼다 (합의 9-1) */
    private static final String TOUCH = """
            UPDATE trusted_access SET last_use_ts = ?
             WHERE mbr_no = ? AND conn_ip = ? AND dvc_id = ?
            """;

    /** 이미 있으면 처음 인증 시각(reg_ts)과 등록 경로는 그대로 두고 마지막 사용 시각만 바꾼다 */
    private static final String UPSERT = """
            INSERT INTO trusted_access (mbr_no, conn_ip, dvc_id, user_agt, reg_dv_cd, reg_ts, last_use_ts)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (mbr_no, conn_ip, dvc_id) DO UPDATE SET last_use_ts = EXCLUDED.last_use_ts
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public TrustedAccessStore(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public boolean isKnownIp(long mbrNo, String connIp) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(KNOWN_IP, Boolean.class, mbrNo, connIp));
    }

    public boolean isKnownDevice(long mbrNo, String dvcId) {
        return dvcId != null
                && Boolean.TRUE.equals(jdbcTemplate.queryForObject(KNOWN_DEVICE, Boolean.class, mbrNo, dvcId));
    }

    public void touch(long mbrNo, RequestMeta meta) {
        jdbcTemplate.update(TOUCH, Timestamp.valueOf(now()), mbrNo, meta.connIp(), meta.dvcId());
    }

    public void register(long mbrNo, String connIp, String dvcId, String userAgt, RegPath path) {
        if (dvcId == null) {
            throw new IllegalArgumentException("신뢰 접속 등록에는 기기 ID(X-Device-Id)가 있어야 한다");
        }
        Timestamp now = Timestamp.valueOf(now());
        jdbcTemplate.update(UPSERT, mbrNo, connIp, dvcId, userAgt, path.name(), now, now);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
