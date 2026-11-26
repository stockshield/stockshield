package com.stockshield.withdraw;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WithdrawalRepository extends JpaRepository<Withdrawal, String> {

    Optional<Withdrawal> findByAcntNoAndIdmpKey(String acntNo, String idmpKey);

    Page<Withdrawal> findByAcntNo(String acntNo, Pageable pageable);
}
