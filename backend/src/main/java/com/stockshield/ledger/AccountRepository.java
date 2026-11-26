package com.stockshield.ledger;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, String> {

    Optional<Account> findByMbrNo(long mbrNo);
}
