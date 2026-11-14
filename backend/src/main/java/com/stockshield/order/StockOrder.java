package com.stockshield.order;

import java.time.LocalDateTime;

import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

import com.stockshield.common.entity.BaseRegTimeEntity;
import com.stockshield.fds.api.RequestMeta;
import com.stockshield.ledger.PriceReader.Quote;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문 - INSERT 전용. 체결·거절 결과를 한 번 저장하고 고치지 않음 (TRD_ORD_001 #3, TRD_LED_001 #5).
 * JPQL 예약어(ORDER)와 겹치지 않게 클래스 이름은 StockOrder, 테이블은 orders.
 * 주문번호를 직접 정해 넣으므로 Persistable로 새 행임을 알려 save가 SELECT 없이 INSERT하게 함.
 */
@Getter
@Entity
@Immutable
@Table(name = "orders")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockOrder extends BaseRegTimeEntity implements Persistable<String> {

    public static final String BUY = "BUY";
    public static final String SELL = "SELL";
    public static final String FILLED = "FILLED";
    public static final String REJECTED = "REJECTED";

    @Id
    @Column(name = "ord_no", length = 30, nullable = false, updatable = false)
    private String ordNo;

    @Column(name = "acnt_no", length = 20, nullable = false, updatable = false)
    private String acntNo;

    @Column(name = "tck_cd", length = 10, nullable = false, updatable = false)
    private String tckCd;

    @Column(name = "ord_dv_cd", length = 10, nullable = false, updatable = false)
    private String ordDvCd;

    @Column(name = "ord_qty", nullable = false, updatable = false)
    private Long ordQty;

    /** 이번 요청이 읽은 시세. 거절에도 남기고, 시세가 없었으면 prcTs와 함께 null */
    @Column(name = "ord_prc", columnDefinition = "numeric(15,0)", updatable = false)
    private Long ordPrc;

    @Column(name = "prc_ts", updatable = false)
    private LocalDateTime prcTs;

    @Column(name = "ord_stat_cd", length = 10, nullable = false, updatable = false)
    private String ordStatCd;

    @Column(name = "rjct_rsn_cd", length = 30, updatable = false)
    private String rjctRsnCd;

    @Column(name = "idmp_key", length = 36, nullable = false, updatable = false)
    private String idmpKey;

    @Column(name = "conn_ip", length = 45, nullable = false, updatable = false)
    private String connIp;

    @Column(name = "dvc_id", length = 64, updatable = false)
    private String dvcId;

    @Column(name = "user_agt", length = 255, nullable = false, updatable = false)
    private String userAgt;

    @Column(name = "trc_id", length = 36, nullable = false, updatable = false)
    private String trcId;

    @Transient
    private boolean isNew = true;

    /** @param rjctRsnCd null이면 체결 */
    static StockOrder of(String ordNo, String acntNo, OrderCommand cmd, Quote quote, String rjctRsnCd,
                         RequestMeta meta, String trcId) {
        StockOrder o = new StockOrder();
        o.ordNo = ordNo;
        o.acntNo = acntNo;
        o.tckCd = cmd.tckCd();
        o.ordDvCd = cmd.ordDvCd();
        o.ordQty = cmd.ordQty();
        o.ordPrc = quote == null ? null : quote.currentPrice();
        o.prcTs = quote == null ? null : quote.prcTs();
        o.ordStatCd = rjctRsnCd == null ? FILLED : REJECTED;
        o.rjctRsnCd = rjctRsnCd;
        o.idmpKey = cmd.idmpKey();
        o.connIp = meta.connIp();
        o.dvcId = meta.dvcId();
        o.userAgt = meta.userAgt();
        o.trcId = trcId;
        return o;
    }

    public boolean isFilled() {
        return FILLED.equals(ordStatCd);
    }

    /** 같은 키에 다른 내용인지 볼 때 비교하는 값: 종목·구분·수량 (TRD_ORD_001 #4) */
    boolean sameContent(OrderCommand cmd) {
        return tckCd.equals(cmd.tckCd()) && ordDvCd.equals(cmd.ordDvCd()) && ordQty == cmd.ordQty();
    }

    @Override
    public String getId() {
        return ordNo;
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
