package com.stockshield.auth;

import java.time.Duration;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Refresh Token 저장소. auth:refresh:{jti} = 회원번호, TTL 7일 (USR_AUTH_002 구현방안, 합의서 9절 키 영역).
 * 로그아웃하면 지워 재발급을 막는다(USR_AUTH_002 #4). 토큰 원문은 저장하지 않는다.
 * Redis를 쓸 수 없으면 RedisUnavailableException.
 */
@Component
public class RefreshTokenStore {

    static final String KEY = "auth:refresh:";

    private final StringRedisTemplate redis;

    public RefreshTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void save(String jti, long mbrNo, Duration ttl) {
        try {
            redis.opsForValue().set(KEY + jti, String.valueOf(mbrNo), ttl);
        } catch (DataAccessException e) {
            throw new RedisUnavailableException(e);
        }
    }

    /** 이 jti가 그 회원의 살아 있는 Refresh Token인지 */
    public boolean isActive(String jti, long mbrNo) {
        try {
            return String.valueOf(mbrNo).equals(redis.opsForValue().get(KEY + jti));
        } catch (DataAccessException e) {
            throw new RedisUnavailableException(e);
        }
    }

    public void delete(String jti) {
        try {
            redis.delete(KEY + jti);
        } catch (DataAccessException e) {
            throw new RedisUnavailableException(e);
        }
    }
}
