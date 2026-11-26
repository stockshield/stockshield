package com.stockshield.member;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {

    boolean existsByLgnId(String lgnId);

    Optional<Member> findByLgnId(String lgnId);
}
