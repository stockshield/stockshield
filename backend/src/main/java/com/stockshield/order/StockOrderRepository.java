package com.stockshield.order;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockOrderRepository extends JpaRepository<StockOrder, String> {

    Optional<StockOrder> findByAcntNoAndIdmpKey(String acntNo, String idmpKey);

    Page<StockOrder> findByAcntNo(String acntNo, Pageable pageable);

    Page<StockOrder> findByAcntNoAndOrdStatCd(String acntNo, String ordStatCd, Pageable pageable);
}
