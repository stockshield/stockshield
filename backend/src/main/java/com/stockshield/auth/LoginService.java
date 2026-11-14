package com.stockshield.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.stockshield.common.config.ClockConfig;
import com.stockshield.common.error.ApiException;
import com.stockshield.common.error.ErrorCode;
import com.stockshield.fds.api.ConfirmResult;
import com.stockshield.fds.api.FdsService;
import com.stockshield.fds.api.LoginAccessCommand;
import com.stockshield.fds.api.LoginAccessResult;
import com.stockshield.fds.api.RequestMeta;
import com.stockshield.member.Member;
import com.stockshield.member.MemberRepository;

/**
 * 로그인, 새 접속 이메일 인증 확인, 인증 코드 재발송 (USR_AUTH_002, FDS_LGN_001, API 명세서 'API_명세서' 5~7행, 연결 합의서 5절).
 *
 * 이 클래스의 메서드에는 @Transactional을 붙이지 않는다(합의서 2절 표). FDS ① 메서드는 REQUIRES_NEW라 돌아올 때 이미 커밋되어 있어,
 * 메일 발송이 DB 트랜잭션 밖에서 일어나고 503으로 끝나도 탐지 기록(PENDING → FAIL)이 남는다.
 *
 * 로그인 순서: 입력 확인(400) → 잠금 확인(423) → 아이디·비밀번호(401, 이력 N) → checkLoginAccess → 이력 Y
 *   → KNOWN: 200 토큰 / NEW_ACCESS: 코드 발급(TTL = expiresAt − 현재)·메일 → 202. 메일·Redis 장애면 failLoginAccess 후 503.
 */
@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    static final int LGN_ID_MAX = 50;
    /** BCrypt는 72바이트 뒤를 버린다. 가입 규칙(8~72자 ASCII)보다 긴 값은 비교하지 않고 불일치로 본다 */
    static final int LGN_PWD_MAX = 72;
    static final int AUTH_ID_MAX = 64;
    private static final Pattern AUTH_CODE = Pattern.compile("^\\d{6}$");

    /** 로그인 결과: 토큰(200) 또는 새 접속 인증 요청(202) */
    public sealed interface LoginResult {
        record Authenticated(TokenService.Tokens tokens) implements LoginResult {
        }

        record AuthRequired(String authId, String mskEmlAddr) implements LoginResult {
        }
    }

    private final MemberRepository members;
    private final PasswordEncoder passwordEncoder;
    private final LoginFailCounter failCounter;
    private final LoginHistoryRepository history;
    private final FdsService fdsService;
    private final AuthCodeStore codes;
    private final AuthCodeMailSender mailSender;
    private final TokenService tokens;
    private final Clock clock;
    /** 없는 아이디도 BCrypt 비교를 한 번 해 응답 시간으로 아이디가 있는지 드러나지 않게 한다 */
    private final String dummyHash;

    public LoginService(MemberRepository members, PasswordEncoder passwordEncoder, LoginFailCounter failCounter,
                        LoginHistoryRepository history, FdsService fdsService, AuthCodeStore codes,
                        AuthCodeMailSender mailSender, TokenService tokens, Clock clock) {
        this.members = members;
        this.passwordEncoder = passwordEncoder;
        this.failCounter = failCounter;
        this.history = history;
        this.fdsService = fdsService;
        this.codes = codes;
        this.mailSender = mailSender;
        this.tokens = tokens;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public LoginResult login(String lgnId, String lgnPwd, RequestMeta meta) {
        if (lgnId == null || lgnId.isBlank() || lgnId.length() > LGN_ID_MAX || lgnPwd == null || lgnPwd.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        Instant now = clock.instant();
        if (failCounter.isLocked(lgnId, now)) {
            throw new ApiException(ErrorCode.LOGIN_LOCKED);
        }

        Optional<Member> found = members.findByLgnId(lgnId);
        if (!passwordMatches(found, lgnPwd)) {
            history.save(LoginHistory.of(lgnId, meta, false, false));
            boolean lockedNow = failCounter.recordFailure(lgnId, now);
            // 5회째 실패는 그 응답부터 잠금을 알린다. 남은 횟수는 주지 않는다(대기 목록 #13)
            throw new ApiException(lockedNow ? ErrorCode.LOGIN_LOCKED : ErrorCode.LOGIN_FAILED);
        }
        failCounter.reset(lgnId);
        Member member = found.get();

        LoginAccessResult access = fdsService.checkLoginAccess(new LoginAccessCommand(member.getMbrNo(), meta));
        boolean newAccess = access.type() == LoginAccessResult.Type.NEW_ACCESS;
        history.save(LoginHistory.of(lgnId, meta, true, newAccess));

        if (!newAccess) {
            return new LoginResult.Authenticated(tokens.issueOnKnownLogin(member));
        }
        return requestEmailAuth(member, access);
    }

    /** 새 접속 인증 확인 (API 6행, 합의서 5절) */
    public TokenService.Tokens verifyNewAccess(String authId, String authCode) {
        validateCodeInput(authId, authCode);
        AuthCodeStore.Verification v;
        try {
            v = codes.verify(authId, AuthPurpose.LOGIN, authCode, null, clock.instant());
        } catch (RedisUnavailableException e) {
            log.warn("Redis 장애로 새 접속 인증 코드를 확인할 수 없습니다");
            throw new ApiException(ErrorCode.AUTH_UNAVAILABLE);
        }
        switch (v.outcome()) {
            case MISMATCH -> throw new ApiException(ErrorCode.AUTH_CODE_MISMATCH, Map.of("remainCnt", v.remainCnt()));
            case LOCKED_OUT -> {
                // 5회째 틀림: 탐지 기록을 FAIL로 닫고 로그인부터 다시 (FDS_LGN_001 #4)
                fdsService.failLoginAccess(v.dtcSeq());
                throw new ApiException(ErrorCode.AUTH_CODE_EXPIRED);
            }
            case GONE -> throw new ApiException(ErrorCode.AUTH_CODE_EXPIRED);
            case OK -> {
                // 아래로
            }
        }
        if (fdsService.confirmLoginAccess(v.dtcSeq()) == ConfirmResult.EXPIRED) {
            throw new ApiException(ErrorCode.AUTH_CODE_EXPIRED);
        }
        Member member = members.findById(v.mbrNo()).orElseThrow(() -> new ApiException(ErrorCode.AUTH_CODE_EXPIRED));
        return tokens.issueAfterNewAccess(member);
    }

    /**
     * 새 접속 인증 코드 재발송 (API 7행). FDS를 부르지 않는다. 60초 간격(429), 이전 코드 무효,
     * 남은 초(expiresAt − 현재)를 돌려주며 늘리지 않는다. 만료·5회 오류로 무효가 된 authId는 410(대기 목록 #30).
     */
    public long resend(String authId) {
        if (authId == null || authId.isBlank() || authId.length() > AUTH_ID_MAX) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        Instant now = clock.instant();
        String code = codes.newCode();
        AuthCodeStore.Resend r;
        try {
            r = codes.resend(authId, code, now);
        } catch (RedisUnavailableException e) {
            throw new ApiException(ErrorCode.AUTH_UNAVAILABLE);
        }
        switch (r.outcome()) {
            case GONE -> throw new ApiException(ErrorCode.AUTH_CODE_EXPIRED);
            case TOO_SOON -> throw new ApiException(ErrorCode.TOO_MANY_REQUESTS);
            case OK -> {
                // 아래로
            }
        }
        try {
            mailSender.send(r.emlAddr(), code, AuthPurpose.LOGIN);
        } catch (MailException e) {
            log.warn("새 접속 인증 코드 재발송 메일을 보내지 못했습니다 authId={}", authId, e);
            try {
                codes.allowResendNow(authId);
            } catch (RedisUnavailableException ignored) {
                // 60초 뒤 다시 보낼 수 있다
            }
            throw new ApiException(ErrorCode.AUTH_UNAVAILABLE);
        }
        return Math.max(0L, Duration.between(now, r.expiresAt()).toSeconds());
    }

    private LoginResult requestEmailAuth(Member member, LoginAccessResult access) {
        long dtcSeq = access.dtcSeq();
        Instant now = clock.instant();
        Instant expiresAt = access.expiresAt().atZone(ClockConfig.ZONE).toInstant();
        String authId = UUID.randomUUID().toString();
        String code = codes.newCode();
        try {
            codes.issueLogin(authId, code, member.getMbrNo(), dtcSeq, member.getEmlAddr(), now, expiresAt);
        } catch (RedisUnavailableException e) {
            log.warn("Redis 장애로 새 접속 인증을 할 수 없어 로그인을 막습니다 mbrNo={} dtcSeq={}", member.getMbrNo(), dtcSeq);
            fdsService.failLoginAccess(dtcSeq);
            throw new ApiException(ErrorCode.AUTH_UNAVAILABLE);
        }
        try {
            mailSender.send(member.getEmlAddr(), code, AuthPurpose.LOGIN);
        } catch (MailException e) {
            log.warn("메일 장애로 새 접속 인증을 할 수 없어 로그인을 막습니다 mbrNo={} dtcSeq={}", member.getMbrNo(), dtcSeq, e);
            try {
                codes.discard(authId);
            } catch (RedisUnavailableException ignored) {
                // 코드는 Redis TTL로 사라진다
            }
            fdsService.failLoginAccess(dtcSeq);
            throw new ApiException(ErrorCode.AUTH_UNAVAILABLE);
        }
        return new LoginResult.AuthRequired(authId, maskEmail(member.getEmlAddr()));
    }

    private boolean passwordMatches(Optional<Member> found, String lgnPwd) {
        if (lgnPwd.length() > LGN_PWD_MAX) {
            return false;
        }
        if (found.isEmpty()) {
            passwordEncoder.matches(lgnPwd, dummyHash);
            return false;
        }
        return passwordEncoder.matches(lgnPwd, found.get().getLgnPwd());
    }

    static void validateCodeInput(String authId, String authCode) {
        if (authId == null || authId.isBlank() || authId.length() > AUTH_ID_MAX
                || authCode == null || !AUTH_CODE.matcher(authCode).matches()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
    }

    /** 화면에 보여 줄 가린 주소. 앞 2자(1~2자 주소는 1자)만 남긴다: yjjung@gmail.com → yj***@gmail.com (FDS_LGN_001 #2) */
    static String maskEmail(String emlAddr) {
        int at = emlAddr.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = emlAddr.substring(0, at);
        int keep = local.length() <= 2 ? 1 : 2;
        return local.substring(0, keep) + "***" + emlAddr.substring(at);
    }
}
