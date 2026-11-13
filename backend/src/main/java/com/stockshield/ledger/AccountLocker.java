package com.stockshield.ledger;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 계좌 락 (TRD_LED_001 #2, 테이블 정의서 '공통 규칙' 4행).
 * 주문·출금·관리자 잠금/해제가 같은 계좌를 하나씩 처리하게 하는 유일한 락.
 */
@Repository
public class AccountLocker {

    @PersistenceContext
    private EntityManager em;

    /**
     * 트랜잭션 안에서만 부름(밖이면 TransactionRequiredException).
     * 2초 넘게 기다리면 SQLSTATE 55P03으로 실패하고 GlobalExceptionHandler가 503 LOCK_TIMEOUT으로 바꿈.
     */
    public Optional<Account> lockByMbrNo(long mbrNo) {
        return lock("mbr_no", mbrNo);
    }

    /** 관리자 잠금·해제용 (API 명세서 'API_명세서' 27행) */
    public Optional<Account> lockByAcntNo(String acntNo) {
        return lock("acnt_no", acntNo);
    }

    private Optional<Account> lock(String column, Object value) {
        // SET LOCAL은 트랜잭션이 끝나면 풀려, 풀에 돌아간 연결에 남지 않음
        em.createNativeQuery("SET LOCAL lock_timeout = '2s'").executeUpdate();
        // JPA PESSIMISTIC_WRITE는 FOR UPDATE를 만들어 외래 키 확인 락(FOR KEY SHARE)과 부딪히므로 네이티브 쿼리로 씀
        List<?> rows = em.createNativeQuery(
                        "SELECT * FROM account WHERE " + column + " = :value FOR NO KEY UPDATE", Account.class)
                .setParameter("value", value)
                .getResultList();
        return rows.stream().findFirst().map(Account.class::cast);
    }
}
