package com.stockshield.ledger;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeHistoryRepository extends JpaRepository<TradeHistory, Long> {

    Optional<TradeHistory> findByOrdNo(String ordNo);
}
