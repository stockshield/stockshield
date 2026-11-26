package com.stockshield.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.data.domain.Persistable;

import com.stockshield.common.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 보유 종목 잔고. 테이블 정의서 '테이블정의서' holding.
 * 계좌 락 안에서만 바뀌므로 이 행에는 락을 걸지 않음(락 순서가 하나라 교착 없음, TRD_LED_001 #2).
 */
@Getter
@Entity
@IdClass(HoldingId.class)
@Table(name = "holding")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Holding extends BaseTimeEntity implements Persistable<HoldingId> {

    private static final BigDecimal ZERO_PRC = BigDecimal.ZERO.setScale(2);

    @Id
    @Column(name = "acnt_no", length = 20, nullable = false, updatable = false)
    private String acntNo;

    @Id
    @Column(name = "tck_cd", length = 10, nullable = false, updatable = false)
    private String tckCd;

    @Column(name = "hld_qty", nullable = false)
    private Long hldQty;

    @Column(name = "avg_buy_prc", precision = 15, scale = 2, nullable = false)
    private BigDecimal avgBuyPrc;

    @Transient
    private boolean isNew = true;

    public static Holding empty(String acntNo, String tckCd) {
        Holding h = new Holding();
        h.acntNo = acntNo;
        h.tckCd = tckCd;
        h.hldQty = 0L;
        h.avgBuyPrc = ZERO_PRC;
        return h;
    }

    /** 가중평균 단가, 소수 둘째 자리 HALF_UP (테이블 정의서 '공통 규칙' 3행) */
    public void buy(long qty, long prc) {
        BigDecimal cost = avgBuyPrc.multiply(BigDecimal.valueOf(hldQty))
                .add(BigDecimal.valueOf(prc).multiply(BigDecimal.valueOf(qty)));
        hldQty = Math.addExact(hldQty, qty);
        avgBuyPrc = cost.divide(BigDecimal.valueOf(hldQty), 2, RoundingMode.HALF_UP);
    }

    /** 매도는 평균 단가를 그대로 두고, 다 팔면 0으로 되돌림 */
    public void sell(long qty) {
        if (qty <= 0 || hldQty < qty) {
            throw new IllegalStateException("보유 수량보다 많이 팔 수 없습니다 acntNo=" + acntNo + " tckCd=" + tckCd);
        }
        hldQty -= qty;
        if (hldQty == 0) {
            avgBuyPrc = ZERO_PRC;
        }
    }

    @Override
    public HoldingId getId() {
        return new HoldingId(acntNo, tckCd);
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
