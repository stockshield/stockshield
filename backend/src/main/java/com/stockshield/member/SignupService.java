package com.stockshield.member;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mail.MailException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.stockshield.auth.AuthCodeMailSender;
import com.stockshield.auth.AuthCodeStore;
import com.stockshield.auth.AuthPurpose;
import com.stockshield.auth.RedisUnavailableException;
import com.stockshield.common.error.ApiException;
import com.stockshield.common.error.ErrorCode;
import com.stockshield.fds.api.RequestMeta;

/**
 * 아이디 중복 확인, 가입 인증 메일, 회원가입 (USR_AUTH_001, API 명세서 'API_명세서' 2~4행).
 *
 * 가입 순서(문서 수정 대기 목록 #24): 입력 검증(400) → 아이디 중복(409, 인증 코드를 쓰지 않음) → 인증 코드 확인
 * (authId에 묶인 이메일과 요청 이메일이 같고 코드가 맞으면 Redis에서 확인과 삭제를 한 번에) → 가입 트랜잭션.
 * Redis 삭제와 DB 커밋은 한 트랜잭션으로 묶을 수 없어, 가입 트랜잭션이 실패해도 코드는 되살리지 않는다(1회용을 지킨다, 다시 발송받음).
 * 메일은 DB 트랜잭션 밖에서 보낸다. 이 클래스에는 @Transactional을 붙이지 않는다.
 */
@Service
public class SignupService {

    private static final Logger log = LoggerFactory.getLogger(SignupService.class);

    /** 가입 인증 메일 발송 결과 {authId, ttlSec} (API 3행) */
    public record EmailCodeIssued(String authId, long ttlSec) {
    }

    private final MemberRepository members;
    private final MemberRegistrar registrar;
    private final AuthCodeStore codes;
    private final AuthCodeMailSender mailSender;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public SignupService(MemberRepository members, MemberRegistrar registrar, AuthCodeStore codes,
                         AuthCodeMailSender mailSender, PasswordEncoder passwordEncoder, Clock clock) {
        this.members = members;
        this.registrar = registrar;
        this.codes = codes;
        this.mailSender = mailSender;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /** 2행: 형식이 틀린 아이디는 400 */
    public boolean isAvailable(String lgnId) {
        return !members.existsByLgnId(SignupRules.lgnId(lgnId));
    }

    /**
     * 3행: 6자리 코드를 보낸다. 같은 주소 60초 간격(429), 다시 부르면 새 authId·새 5분이고 이전 코드는 무효(대기 목록 #18).
     * Redis 장애·메일 발송 실패는 503 AUTH_UNAVAILABLE(대기 목록 #15).
     */
    public EmailCodeIssued issueEmailCode(String emlAddr) {
        String eml = SignupRules.email(emlAddr);
        Instant now = clock.instant();
        String authId = UUID.randomUUID().toString();
        String code = codes.newCode();
        boolean issued;
        try {
            issued = codes.issueSignup(eml, authId, code, now);
        } catch (RedisUnavailableException e) {
            log.warn("Redis 장애로 가입 인증 코드를 발급하지 못했습니다");
            throw new ApiException(ErrorCode.AUTH_UNAVAILABLE);
        }
        if (!issued) {
            throw new ApiException(ErrorCode.TOO_MANY_REQUESTS);
        }
        try {
            mailSender.send(eml, code, AuthPurpose.SIGNUP);
        } catch (MailException e) {
            log.warn("가입 인증 메일을 보내지 못했습니다 authId={}", authId, e);
            try {
                codes.cancelSignup(eml, authId);
            } catch (RedisUnavailableException ignored) {
                // 코드는 Redis TTL로 사라진다
            }
            throw new ApiException(ErrorCode.AUTH_UNAVAILABLE);
        }
        return new EmailCodeIssued(authId, AuthCodeStore.SIGNUP_TTL.toSeconds());
    }

    /** 4행: 201 {mbrNo, acntNo, initDpsAmt, dpsBal}. 토큰은 주지 않는다(가입 뒤 로그인) */
    public MemberRegistrar.Registered signUp(MemberController.SignupRequest request, RequestMeta meta) {
        SignupRules.SignupInput in = SignupRules.validate(request);
        if (members.existsByLgnId(in.lgnId())) {
            throw new ApiException(ErrorCode.LGN_ID_DUPLICATED);
        }

        AuthCodeStore.Verification v;
        try {
            v = codes.verify(in.authId(), AuthPurpose.SIGNUP, in.authCode(), in.emlAddr(), clock.instant());
        } catch (RedisUnavailableException e) {
            throw new ApiException(ErrorCode.AUTH_UNAVAILABLE);
        }
        switch (v.outcome()) {
            // 이메일이 다른 것도 코드가 틀린 것과 같이 알린다(어느 값이 틀렸는지 알려 주지 않음, 대기 목록 #20)
            case MISMATCH -> throw new ApiException(ErrorCode.AUTH_CODE_MISMATCH, Map.of("remainCnt", v.remainCnt()));
            case LOCKED_OUT, GONE -> throw new ApiException(ErrorCode.AUTH_CODE_EXPIRED);
            case OK -> {
                // 아래로
            }
        }

        String lgnPwdHash = passwordEncoder.encode(in.lgnPwd());
        String wdrPwdHash = passwordEncoder.encode(in.wdrPwd());
        try {
            return registrar.register(in.lgnId(), lgnPwdHash, in.mbrNm(), in.emlAddr(), in.initPkg(), wdrPwdHash, meta);
        } catch (DataIntegrityViolationException e) {
            // 중복 확인과 저장 사이에 같은 아이디가 먼저 가입했으면 UK_MEMBER_01이 막는다
            if (members.existsByLgnId(in.lgnId())) {
                throw new ApiException(ErrorCode.LGN_ID_DUPLICATED);
            }
            throw e;
        }
    }
}
