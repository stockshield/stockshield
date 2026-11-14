package com.stockshield.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.stockshield.common.error.ApiException;
import com.stockshield.common.error.ErrorCode;
import com.stockshield.member.Member;
import com.stockshield.member.MemberRepository;

/**
 * 토큰 발급·재발급·로그아웃 (USR_AUTH_002 #1·#4·#5, API 명세서 'API_명세서' 5·6·8·9행).
 * DB 트랜잭션을 열지 않는다. Redis(Refresh Token)만 쓴다.
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    /** 로그인·인증 확인 응답 본문 {accessToken, refreshToken, roleCd} */
    public record Tokens(String accessToken, String refreshToken, String roleCd) {
    }

    private final JwtProvider jwtProvider;
    private final RefreshTokenStore refreshTokens;
    private final MemberRepository members;

    public TokenService(JwtProvider jwtProvider, RefreshTokenStore refreshTokens, MemberRepository members) {
        this.jwtProvider = jwtProvider;
        this.refreshTokens = refreshTokens;
        this.members = members;
    }

    /**
     * 아는 IP·기기 로그인. Redis 장애면 Access Token만 준다(refreshToken = null, 30분 뒤 다시 로그인, USR_AUTH_002 #5).
     */
    public Tokens issueOnKnownLogin(Member member) {
        String access = jwtProvider.createAccess(member.getMbrNo(), member.getRoleCd());
        try {
            return new Tokens(access, saveRefresh(member), member.getRoleCd());
        } catch (RedisUnavailableException e) {
            log.warn("Redis 장애로 Refresh Token 없이 Access Token만 발급합니다 mbrNo={}", member.getMbrNo());
            return new Tokens(access, null, member.getRoleCd());
        }
    }

    /**
     * 새 접속 인증 성공. 응답의 refreshToken이 필수라 저장할 수 없으면 503 AUTH_UNAVAILABLE
     * (API 6행, 합의서 5절. 신뢰 접속은 이미 등록되어 다음 로그인은 KNOWN이다).
     */
    public Tokens issueAfterNewAccess(Member member) {
        try {
            String refresh = saveRefresh(member);
            return new Tokens(jwtProvider.createAccess(member.getMbrNo(), member.getRoleCd()), refresh, member.getRoleCd());
        } catch (RedisUnavailableException e) {
            log.warn("Redis 장애로 새 접속 인증 뒤 토큰을 저장하지 못했습니다 mbrNo={}", member.getMbrNo());
            throw new ApiException(ErrorCode.AUTH_UNAVAILABLE);
        }
    }

    /** Access Token 재발급. 만료·조작·로그아웃된 Refresh Token은 401, Redis 장애는 503 (API 8행) */
    public String refresh(String refreshToken) {
        JwtProvider.IssuedRefresh parsed = jwtProvider.parseRefresh(refreshToken)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        boolean active;
        try {
            active = refreshTokens.isActive(parsed.jti(), parsed.mbrNo());
        } catch (RedisUnavailableException e) {
            throw new ApiException(ErrorCode.AUTH_UNAVAILABLE);
        }
        if (!active) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        Member member = members.findById(parsed.mbrNo()).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        return jwtProvider.createAccess(member.getMbrNo(), member.getRoleCd());
    }

    /**
     * 로그아웃: 그 회원의 Refresh Token을 Redis에서 지운다(USR_AUTH_002 #4). 이미 없거나 다른 사람의 토큰이면 아무것도 하지 않는다.
     * Redis 장애면 지웠다고 거짓으로 답하지 않고 503 AUTH_UNAVAILABLE.
     */
    public void logout(long mbrNo, String refreshToken) {
        jwtProvider.parseRefresh(refreshToken)
                .filter(r -> r.mbrNo() == mbrNo)
                .ifPresent(r -> {
                    try {
                        refreshTokens.delete(r.jti());
                    } catch (RedisUnavailableException e) {
                        throw new ApiException(ErrorCode.AUTH_UNAVAILABLE);
                    }
                });
    }

    private String saveRefresh(Member member) {
        JwtProvider.IssuedRefresh refresh = jwtProvider.createRefresh(member.getMbrNo());
        refreshTokens.save(refresh.jti(), member.getMbrNo(), JwtProvider.REFRESH_TTL);
        return refresh.token();
    }
}
