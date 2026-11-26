package com.stockshield.member;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.stockshield.fds.api.FdsService;
import com.stockshield.fds.api.RequestMeta;
import com.stockshield.ledger.Account;
import com.stockshield.ledger.AccountNumbers;
import com.stockshield.ledger.AccountRepository;
import com.stockshield.ledger.InitPkg;

/**
 * 가입 트랜잭션: 회원 저장, 계좌 개설, 첫 기기 등록(FDS registerSignupAccess, MANDATORY)을 한 트랜잭션으로 한다.
 * 하나라도 실패하면 모두 되돌려 계좌 없는 회원이 생기지 않는다 (USR_AUTH_001 #4·구현방안 '하나의 트랜잭션', 합의서 2절 표).
 * BCrypt 계산은 트랜잭션을 짧게 하려고 호출하는 쪽(SignupService)에서 먼저 한다.
 */
@Component
public class MemberRegistrar {

    /** 가입 응답 본문 {mbrNo, acntNo, initDpsAmt, dpsBal} (API 4행) */
    public record Registered(long mbrNo, String acntNo, long initDpsAmt, long dpsBal) {
    }

    private final MemberRepository members;
    private final AccountRepository accounts;
    private final AccountNumbers accountNumbers;
    private final FdsService fdsService;

    public MemberRegistrar(MemberRepository members, AccountRepository accounts, AccountNumbers accountNumbers,
                           FdsService fdsService) {
        this.members = members;
        this.accounts = accounts;
        this.accountNumbers = accountNumbers;
        this.fdsService = fdsService;
    }

    @Transactional
    public Registered register(String lgnId, String lgnPwdHash, String mbrNm, String emlAddr,
                               InitPkg initPkg, String wdrPwdHash, RequestMeta meta) {
        Member member = members.save(Member.user(lgnId, lgnPwdHash, mbrNm, emlAddr));
        Account account = accounts.save(Account.open(accountNumbers.next(), member.getMbrNo(), initPkg, wdrPwdHash));
        fdsService.registerSignupAccess(member.getMbrNo(), meta);
        return new Registered(member.getMbrNo(), account.getAcntNo(), account.getInitDpsAmt(), account.getDpsBal());
    }
}
