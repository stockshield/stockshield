package com.stockshield.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 이메일 인증 코드(가입·새 접속)를 Redis에 저장하고 확인한다. 가입과 새 접속이 같은 규칙을 쓴다.
 * (USR_AUTH_001 #1, FDS_LGN_001 #2·#4, SYS_SEC_001 #1, 연결 합의서 1·5절·9절 키 영역 auth:*)
 *
 * <pre>
 * auth:code:{authId}   Hash  pur(SIGNUP|LOGIN), hash, fail, exp, sent, eml, mbr, dtc   TTL = exp − 현재
 * auth:signup:{eml}    Hash  authId, sent    같은 주소의 가장 최근 가입 코드 (60초 간격, 이전 코드 무효)
 * </pre>
 *
 * - 코드는 SecureRandom 6자리이고 Redis에는 SHA-256(authId:code)만 둔다. 메일로만 나가고 로그에 남기지 않는다.
 * - 유효·만료와 재발송 간격은 Redis TTL이 아니라 Clock 시각(exp, sent)으로 판단한다. 고정 Clock 테스트로 5분·60초 경계를 증명하기 위해서다.
 *   유효는 현재 < exp(= 첫 발송 + 5분, 새 접속은 FDS의 expiresAt)이고, 탐지 기록의 reg_ts > 현재 − 5분과 같은 시각에 끝난다(문서 수정 대기 목록 #49).
 * - 확인은 Lua 한 번으로 한다. 맞으면 같은 연산 안에서 지워 한 번만 쓰이고(동시 가입 2건 중 1건만 통과, 대기 목록 #24),
 *   틀리면 오류 횟수를 올리고 5회째에 지운다. 동시에 틀려도 횟수가 빠지지 않는다.
 * - Redis를 쓸 수 없으면 RedisUnavailableException을 던진다.
 */
@Component
public class AuthCodeStore {

    public static final Duration SIGNUP_TTL = Duration.ofMinutes(5);
    public static final Duration RESEND_INTERVAL = Duration.ofSeconds(60);
    public static final int MAX_FAILURES = 5;

    static final String CODE_KEY = "auth:code:";
    static final String SIGNUP_KEY = "auth:signup:";

    /** 확인 결과. MISMATCH면 remainCnt, OK면 저장해 둔 회원·탐지 번호(새 접속)와 이메일, LOCKED_OUT이면 탐지 번호 */
    public record Verification(Outcome outcome, int remainCnt, Long mbrNo, Long dtcSeq, String emlAddr) {
        public enum Outcome {
            /** 맞음. 코드는 지워졌다 */
            OK,
            /** 틀림(1~4회째). 오류 횟수를 셌다 */
            MISMATCH,
            /** 5회째 틀림. 코드는 지워졌다 */
            LOCKED_OUT,
            /** 없음·만료·이미 쓴 코드·다른 쓰임의 코드 */
            GONE
        }
    }

    /** 재발송 결과. OK면 바뀌지 않은 만료 시각과 받을 주소 */
    public record Resend(Outcome outcome, Instant expiresAt, String emlAddr) {
        public enum Outcome { OK, TOO_SOON, GONE }
    }

    private static final RedisScript<Long> ISSUE_SIGNUP = new DefaultRedisScript<>("""
            local sent = redis.call('HGET', KEYS[1], 'sent')
            if sent and (tonumber(ARGV[3]) - tonumber(sent)) < tonumber(ARGV[6]) then
              return 0
            end
            local prev = redis.call('HGET', KEYS[1], 'authId')
            if prev then redis.call('DEL', ARGV[8] .. prev) end
            redis.call('HSET', KEYS[2], 'pur', 'SIGNUP', 'hash', ARGV[2], 'fail', '0', 'exp', ARGV[4],
                       'sent', ARGV[3], 'eml', ARGV[7], 'mbr', '', 'dtc', '')
            redis.call('PEXPIRE', KEYS[2], ARGV[5])
            redis.call('HSET', KEYS[1], 'authId', ARGV[1], 'sent', ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[5])
            return 1
            """, Long.class);

    private static final RedisScript<Long> CANCEL_SIGNUP = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'authId') == ARGV[1] then redis.call('DEL', KEYS[1]) end
            redis.call('DEL', KEYS[2])
            return 1
            """, Long.class);

    private static final RedisScript<Long> ISSUE_LOGIN = new DefaultRedisScript<>("""
            redis.call('HSET', KEYS[1], 'pur', 'LOGIN', 'hash', ARGV[1], 'fail', '0', 'exp', ARGV[2],
                       'sent', ARGV[3], 'eml', ARGV[4], 'mbr', ARGV[5], 'dtc', ARGV[6])
            redis.call('PEXPIRE', KEYS[1], ARGV[7])
            return 1
            """, Long.class);

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> VERIFY = new DefaultRedisScript<>("""
            local f = redis.call('HMGET', KEYS[1], 'pur', 'hash', 'fail', 'exp', 'eml', 'mbr', 'dtc')
            if (not f[1]) or f[1] ~= ARGV[1] then return {'GONE'} end
            local max = tonumber(ARGV[5])
            local fail = tonumber(f[3])
            if tonumber(ARGV[4]) >= tonumber(f[4]) or fail >= max then
              redis.call('DEL', KEYS[1])
              return {'GONE'}
            end
            local ok = (f[2] == ARGV[2])
            if ok and ARGV[3] ~= '' then ok = (f[5] == ARGV[3]) end
            if ok then
              redis.call('DEL', KEYS[1])
              return {'OK', f[6], f[7], f[5]}
            end
            fail = fail + 1
            if fail >= max then
              redis.call('DEL', KEYS[1])
              return {'LOCKED_OUT', f[7]}
            end
            redis.call('HSET', KEYS[1], 'fail', tostring(fail))
            return {'MISMATCH', tostring(max - fail)}
            """, List.class);

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> RESEND = new DefaultRedisScript<>("""
            local f = redis.call('HMGET', KEYS[1], 'pur', 'exp', 'sent', 'fail', 'eml')
            if (not f[1]) or f[1] ~= 'LOGIN' then return {'GONE'} end
            local now = tonumber(ARGV[2])
            if now >= tonumber(f[2]) or tonumber(f[4]) >= tonumber(ARGV[4]) then
              redis.call('DEL', KEYS[1])
              return {'GONE'}
            end
            if now - tonumber(f[3]) < tonumber(ARGV[3]) then return {'TOO_SOON'} end
            redis.call('HSET', KEYS[1], 'hash', ARGV[1], 'sent', ARGV[2])
            return {'OK', f[2], f[5]}
            """, List.class);

    private static final RedisScript<Long> ALLOW_RESEND = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('HSET', KEYS[1], 'sent', '0') end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();

    public AuthCodeStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 6자리 숫자 코드 (SecureRandom) */
    public String newCode() {
        return String.format("%06d", random.nextInt(1_000_000));
    }

    /**
     * 가입 코드 발급. 같은 주소로 60초 안에 다시 부르면 false(429).
     * 새 authId·새 5분을 주고 같은 주소의 이전 코드는 지운다 (대기 목록 #18).
     */
    public boolean issueSignup(String emlAddr, String authId, String code, Instant now) {
        Instant exp = now.plus(SIGNUP_TTL);
        Long issued = run(ISSUE_SIGNUP, List.of(SIGNUP_KEY + emlAddr, CODE_KEY + authId),
                authId, hash(authId, code), ms(now), ms(exp), String.valueOf(SIGNUP_TTL.toMillis()),
                String.valueOf(RESEND_INTERVAL.toMillis()), emlAddr, CODE_KEY);
        return issued != null && issued == 1L;
    }

    /** 메일을 보내지 못한 가입 코드를 지워, 60초를 기다리지 않고 바로 다시 요청할 수 있게 한다 */
    public void cancelSignup(String emlAddr, String authId) {
        run(CANCEL_SIGNUP, List.of(SIGNUP_KEY + emlAddr, CODE_KEY + authId), authId);
    }

    /** 새 접속 코드 발급. 유효 시간은 FDS가 돌려준 expiresAt(탐지 기록 reg_ts + 5분)까지 (합의서 5절 '인증 유효 시간') */
    public void issueLogin(String authId, String code, long mbrNo, long dtcSeq, String emlAddr,
                           Instant now, Instant expiresAt) {
        run(ISSUE_LOGIN, List.of(CODE_KEY + authId), hash(authId, code), ms(expiresAt), ms(now), emlAddr,
                String.valueOf(mbrNo), String.valueOf(dtcSeq), String.valueOf(ttlMillis(now, expiresAt)));
    }

    /** 메일을 보내지 못한 새 접속 코드를 지운다 */
    public void discard(String authId) {
        try {
            redis.delete(CODE_KEY + authId);
        } catch (DataAccessException e) {
            throw new RedisUnavailableException(e);
        }
    }

    /**
     * 코드 확인. emlAddr가 있으면(가입) 코드를 받은 주소와 같은지도 함께 보고, 다르면 틀린 코드와 똑같이 센다 (대기 목록 #20).
     */
    @SuppressWarnings("unchecked")
    public Verification verify(String authId, AuthPurpose purpose, String code, String emlAddr, Instant now) {
        List<Object> r = run(VERIFY, List.of(CODE_KEY + authId), purpose.name(), hash(authId, code),
                emlAddr == null ? "" : emlAddr, ms(now), String.valueOf(MAX_FAILURES));
        Verification.Outcome outcome = Verification.Outcome.valueOf(str(r, 0));
        return switch (outcome) {
            case OK -> new Verification(outcome, 0, longOrNull(str(r, 1)), longOrNull(str(r, 2)), str(r, 3));
            case MISMATCH -> new Verification(outcome, Integer.parseInt(str(r, 1)), null, null, null);
            case LOCKED_OUT -> new Verification(outcome, 0, null, longOrNull(str(r, 1)), null);
            case GONE -> new Verification(outcome, 0, null, null, null);
        };
    }

    /**
     * 새 접속 코드 재발송. 60초 간격, 이전 코드는 무효. 만료 시각(대기 목록 #31)과 오류 횟수는 그대로라
     * 재발송을 되풀이해 인증 시간이나 시도 횟수를 늘리지 못한다.
     */
    @SuppressWarnings("unchecked")
    public Resend resend(String authId, String code, Instant now) {
        List<Object> r = run(RESEND, List.of(CODE_KEY + authId), hash(authId, code), ms(now),
                String.valueOf(RESEND_INTERVAL.toMillis()), String.valueOf(MAX_FAILURES));
        Resend.Outcome outcome = Resend.Outcome.valueOf(str(r, 0));
        if (outcome != Resend.Outcome.OK) {
            return new Resend(outcome, null, null);
        }
        return new Resend(outcome, Instant.ofEpochMilli(Long.parseLong(str(r, 1))), str(r, 2));
    }

    /** 재발송 메일을 보내지 못했을 때 60초를 기다리지 않고 다시 시도할 수 있게 한다 */
    public void allowResendNow(String authId) {
        run(ALLOW_RESEND, List.of(CODE_KEY + authId));
    }

    static String hash(String authId, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((authId + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Redis 키 TTL은 실제 시간으로 흐르므로 최소 1초를 둔다. 유효 여부는 exp로 판단한다 */
    static long ttlMillis(Instant now, Instant expiresAt) {
        return Math.max(1_000L, Duration.between(now, expiresAt).toMillis());
    }

    private <T> T run(RedisScript<T> script, List<String> keys, Object... args) {
        try {
            return redis.execute(script, keys, args);
        } catch (DataAccessException e) {
            throw new RedisUnavailableException(e);
        }
    }

    private static String ms(Instant t) {
        return String.valueOf(t.toEpochMilli());
    }

    private static String str(List<Object> r, int i) {
        Object v = r.get(i);
        return v == null ? null : v.toString();
    }

    private static Long longOrNull(String v) {
        return v == null || v.isEmpty() ? null : Long.valueOf(v);
    }
}
