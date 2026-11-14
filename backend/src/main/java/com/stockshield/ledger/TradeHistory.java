package com.stockshield.ledger;

import org.hibernate.annotations.Immutable;

import com.stockshield.common.entity.BaseRegTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 체결 내역 - INSERT 전용. 체결 1건에 1행(UK_TRADE_01), 거래 후 예수금·보유 수량을 함께 남김 (TRD_LED_001 #1·#5).
 * 같은 키 재요청의 201 응답은 orders와 이 행만으로 다시 만듦 (요구사항명세서 부록 #16).
 */
@Getter
@Entity
@Immutable
@Table(name = "trade_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TradeHistory extends BaseRegTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "trd_seq")
    private Long trdSeq;

    @Column(name = "ord_no", length = 30, nullable = false, updatable = false)
    private String ordNo;

    @Column(name = "acnt_no", length = 20, nullable = false, updatable = false)
    private String acntNo;

    @Column(name = "tck_cd", length = 10, nullable = false, updatable = false)
    private String tckCd;

    @Column(name = "trd_dv_cd", length = 10, nullable = false, updatable = false)
    private String trdDvCd;

    @Column(name = "trd_qty", nullable = false, updatable = false)
    private Long trdQty;

    @Column(name = "trd_prc", columnDefinition = "numeric(15,0)", nullable = false, updatable = false)
    private Long trdPrc;

    @Column(name = "trd_amt", columnDefinition = "numeric(19,0)", nullable = false, updatable = false)
    private Long trdAmt;

    @Column(name = "post_dps_bal", columnDefinition = "numeric(19,0)", nullable = false, updatable = false)
    private Long postDpsBal;

    @Column(name = "post_hld_qty", nullable = false, updatable = false)
    private Long postHldQty;

    public static TradeHistory of(String ordNo, String acntNo, String tckCd, String trdDvCd, long trdQty, long trdPrc,
                                  long trdAmt, long postDpsBal, long postHldQty) {
        TradeHistory t = new TradeHistory();
        t.ordNo = ordNo;
        t.acntNo = acntNo;
        t.tckCd = tckCd;
        t.trdDvCd = trdDvCd;
        t.trdQty = trdQty;
        t.trdPrc = trdPrc;
        t.trdAmt = trdAmt;
        t.postDpsBal = postDpsBal;
        t.postHldQty = postHldQty;
        return t;
    }
}
