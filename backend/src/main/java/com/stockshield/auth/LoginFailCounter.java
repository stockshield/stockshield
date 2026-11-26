package com.stockshield.auth;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.stockshield.common.config.ClockConfig;

/**
 * 로그인 비밀번호 연속 실패 잠금: 5회 연속 틀리면 그때부터 30분 동안 로그인을 막는다 (USR_AUTH_002 #3, 423 LOGIN_LOCKED).
 *
 * auth:login-fail:{lgnId} Hash  cnt, last, lockUntil (Clock 시각, 밀리초)
 * - 실패는 30분 안에 이어질 때만 연속으로 센다(마지막 실패에서 30분이 지나면 0부터).
 * - 5회째 실패에서 lockUntil = 그 시각 + 30분을 남기고, 잠금 판단은 현재 < lockUntil이다(Clock 기준이라 고정 Clock으로 30분 경계를 증명한다).
 * - 비밀번호가 맞으면 지운다. 없는 아이디도 같은 키 규칙으로 세어 응답만으로 아이디가 있는지 드러나지 않는다(대기 목록 #13).
 * - Redis 장애: login_history의 최근 30분 시도 중 최근 5건이 모두 실패면 잠김으로 본다(USR_AUTH_002 #5).
 */
@Component
public class LoginFailCounter {

    public static final int MAX_FAILURES = 5;
    public static final Duration WINDOW = Duration.ofMinutes(30);
    public static final Duration LOCK = Duration.ofMinutes(30);
    static final String KEY = "auth:login-fail:";

    private static final Logger log = LoggerFactory.getLogger(LoginFailCounter.class);

    private static final RedisScript<Long> RECORD_FAILURE = new DefaultRedisScript<>("""
            local f = redis.call('HMGET', KEYS[1], 'cnt', 'last')
            local now = tonumber(ARGV[1])
            local cnt = tonumber(f[1] or '0')
            local last = tonumber(f[2] or '0')
            if now - last >= tonumber(ARGV[2]) then cnt = 0 end
            cnt = cnt + 1
            if cnt >= tonumber(ARGV[3]) then
              redis.call('HSET', KEYS[1], 'cnt', '0', 'last', ARGV[1], 'lockUntil', tostring(now + tonumber(ARGV[4])))
              redis.call('PEXPIRE', KEYS[1], ARGV[5])
              return 1
            end
            redis.call('HSET', KEYS[1], 'cnt', tostring(cnt), 'last', ARGV[1])
            redis.call('PEXPIRE', KEYS[1], ARGV[5])
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final LoginHistoryRepository history;

    public LoginFailCounter(StringRedisTemplate redis, LoginHistoryRepository history) {
        this.redis = redis;
        this.history = history;
    }

    /** 지금 잠겨 있는지 */
    public boolean isLocked(String lgnId, Instant now) {
        try {
            Object lockUntil = redis.opsForHash().get(KEY + lgnId, "lockUntil");
            return lockUntil != null && now.toEpochMilli() < Long.parseLong(lockUntil.toString());
        } catch (DataAccessException e) {
            log.warn("Redis 장애로 로그인 실패 횟수를 login_history로 셉니다");
            return lockedByHistory(lgnId, now);
        }
    }

    /**
     * 실패 1회를 센다. 이 실패로 잠겼으면 true.
     * login_history에 이번 실패를 먼저 남긴 뒤 부른다(Redis 장애 때 이력으로 세기 위해).
     */
    public boolean recordFailure(String lgnId, Instant now) {
        try {
            Long locked = redis.execute(RECORD_FAILURE, List.of(KEY + lgnId), String.valueOf(now.toEpochMilli()),
                    String.valueOf(WINDOW.toMillis()), String.valueOf(MAX_FAILURES), String.valueOf(LOCK.toMillis()),
                    String.valueOf(LOCK.plus(WINDOW).toMillis()));
            return locked != null && locked == 1L;
        } catch (DataAccessException e) {
            log.warn("Redis 장애로 로그인 실패 횟수를 login_history로 셉니다");
            return lockedByHistory(lgnId, now);
        }
    }

    /** 비밀번호가 맞으면 연속 실패를 끊는다. Redis 장애면 login_history의 성공 행이 연속을 끊는다 */
    public void reset(String lgnId) {
        try {
            redis.delete(KEY + lgnId);
        } catch (DataAccessException e) {
            log.warn("Redis 장애로 로그인 실패 횟수를 지우지 못했습니다(이력의 성공 행이 대신 연속을 끊는다)");
        }
    }

    boolean lockedByHistory(String lgnId, Instant now) {
        LocalDateTime since = LocalDateTime.ofInstant(now.minus(WINDOW), ClockConfig.ZONE);
        List<String> recent = history.findRecentResults(lgnId, since, PageRequest.of(0, MAX_FAILURES));
        return recent.size() == MAX_FAILURES && recent.stream().allMatch("N"::equals);
    }
}
