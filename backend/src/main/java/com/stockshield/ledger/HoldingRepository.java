package com.stockshield.ledger;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HoldingRepository extends JpaRepository<Holding, HoldingId> {

    /** 다 판 종목(수량 0)은 행이 남아 있어도 보유 종목이 아님 */
    List<Holding> findByAcntNoAndHldQtyGreaterThanOrderByTckCd(String acntNo, long hldQty);
}
