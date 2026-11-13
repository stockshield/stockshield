package com.stockshield.order;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StockOrderRepository extends JpaRepository<StockOrder, String> {

    Optional<StockOrder> findByAcntNoAndIdmpKey(String acntNo, String idmpKey);
}
