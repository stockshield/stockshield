package com.stockshield.auth;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoginHistoryRepository extends JpaRepository<LoginHistory, Long> {

    /** since 이후(초과) 시도의 비밀번호 일치 여부를 최근 것부터. Redis 장애 때 실패 횟수를 센다 (USR_AUTH_002 #5) */
    @Query("""
            select h.succYn from LoginHistory h
             where h.lgnId = :lgnId and h.regTs > :since
             order by h.regTs desc, h.lgnHistSeq desc
            """)
    List<String> findRecentResults(@Param("lgnId") String lgnId, @Param("since") LocalDateTime since, Pageable page);
}
