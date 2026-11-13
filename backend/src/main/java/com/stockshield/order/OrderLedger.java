package com.stockshield.order;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stockshield.common.error.ApiException;
import com.stockshield.common.error.ErrorCode;
import com.stockshield.fds.api.OrderFilledEvent;
import com.stockshield.fds.api.RequestMeta;
import com.stockshield.ledger.Account;
import com.stockshield.ledger.AccountLocker;
import com.stockshield.ledger.Holding;
import com.stockshield.ledger.HoldingId;
import com.stockshield.ledger.HoldingRepository;
import com.stockshield.ledger.LedgerNumbers;
import com.stockshield.ledger.PriceReader;
import com.stockshield.ledger.PriceReader.Quote;
import com.stockshield.ledger.TradeHistory;
import com.stockshield.ledger.TradeHistoryRepository;

/**
 * 주문의 원장 트랜잭션: 계좌 락 → 멱등키 재확인 → 검증 → 원장 반영·결과 저장 (TRD_ORD_001 #2~#4, TRD_LED_001 #1~#4).
 * 업무 거절은 예외로 되돌리지 않고 REJECTED로 커밋함. 예외가 나가면 시스템 오류이고 전부 롤백됨.
 */
@Service
public class OrderLedger {

    private static final Logger log = LoggerFactory.getLogger(OrderLedger.class);

    private final AccountLocker accountLocker;
    private final StockOrderRepository orders;
    private final TradeHistoryRepository trades;
    private final HoldingRepository holdings;
    private final LedgerNumbers numbers;
    private final MarketHours marketHours;
    private final PriceReader priceReader;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public OrderLedger(AccountLocker accountLocker, StockOrderRepository orders, TradeHistoryRepository trades,
                       HoldingRepository holdings, LedgerNumbers numbers, MarketHours marketHours,
                       PriceReader priceReader, ApplicationEventPublisher events, Clock clock) {
        this.accountLocker = accountLocker;
        this.orders = orders;
        this.trades = trades;
        this.holdings = holdings;
        this.numbers = numbers;
        this.marketHours = marketHours;
        this.priceReader = priceReader;
        this.events = events;
        this.clock = clock;
    }

    /**
     * @param quote 트랜잭션 밖에서 읽은 시세(락 점유 시간을 줄임). 시세가 없으면 null
     * @param trcId 이 요청의 거래추적번호
     */
    @Transactional
    public OrderReply place(long mbrNo, OrderCommand cmd, Quote quote, RequestMeta meta, String trcId) {
        Account account = accountLocker.lockByMbrNo(mbrNo)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        String acntNo = account.getAcntNo();

        // 동시에 들어온 같은 키는 락을 기다린 뒤 여기서 먼저 처리된 결과를 받음
        Optional<StockOrder> existing = orders.findByAcntNoAndIdmpKey(acntNo, cmd.idmpKey());
        if (existing.isPresent()) {
            return replay(existing.get(), cmd);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        Holding holding = holdings.findById(new HoldingId(acntNo, cmd.tckCd())).orElse(null);
        ErrorCode reject = rejectReason(account, holding, cmd, quote, now);
        if (reject != null) {
            return OrderReply.of(saveOrder(acntNo, cmd, quote, reject.name(), meta, trcId), null, false);
        }

        long trdAmt = Math.multiplyExact(quote.currentPrice(), cmd.ordQty());
        if (StockOrder.BUY.equals(cmd.ordDvCd())) {
            account.decreaseDpsBal(trdAmt);
            if (holding == null) {
                holding = Holding.empty(acntNo, cmd.tckCd());
                holding.buy(cmd.ordQty(), quote.currentPrice());
                holdings.save(holding);
            } else {
                holding.buy(cmd.ordQty(), quote.currentPrice());
            }
        } else {
            holding.sell(cmd.ordQty());
            account.increaseDpsBal(trdAmt);
        }

        // 계좌·보유 종목 변경도 여기서 함께 나가, 제약 위반이 커밋이 아니라 이 메서드 안에서 드러남
        StockOrder order = saveOrder(acntNo, cmd, quote, null, meta, trcId);
        TradeHistory trade = trades.saveAndFlush(TradeHistory.of(order.getOrdNo(), acntNo, cmd.tckCd(), cmd.ordDvCd(),
                cmd.ordQty(), quote.currentPrice(), trdAmt, account.getDpsBal(), holding.getHldQty()));

        // 체결만 알림. FDS ③ 리스너는 커밋 뒤 별도 스레드에서 돌아 응답과 결과에 영향이 없음 (TRD_ORD_001 #6)
        events.publishEvent(new OrderFilledEvent(order.getOrdNo()));
        return OrderReply.of(order, trade, false);
    }

    /**
     * 같은 키로 이미 저장된 결과를 그대로 돌려줌. 시세·락 없이 부를 수 있음.
     * 로그의 traceId(MDC)는 이번 요청 번호라, 처음 번호를 같이 남기면 한 줄에 두 번호가 남음 (SYS_SEC_001 #6).
     */
    public OrderReply replay(StockOrder order, OrderCommand cmd) {
        if (!order.sameContent(cmd)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        log.info("같은 멱등키 재요청 ordNo={} 처음 요청 traceId={}", order.getOrdNo(), order.getTrcId());
        TradeHistory trade = null;
        if (order.isFilled()) {
            trade = trades.findByOrdNo(order.getOrdNo())
                    .orElseThrow(() -> new IllegalStateException("체결 내역이 없는 체결 주문 ordNo=" + order.getOrdNo()));
        }
        return OrderReply.of(order, trade, true);
    }

    /** 확인 순서: 계좌 상태 → 장 운영 시간 → 시세 지연 → 예수금(매수)·보유 수량(매도). 통과하면 null */
    private ErrorCode rejectReason(Account account, Holding holding, OrderCommand cmd, Quote quote, LocalDateTime now) {
        if (!account.isActive()) {
            return ErrorCode.ACCOUNT_LOCKED;
        }
        if (!marketHours.isOpen(now)) {
            return ErrorCode.MARKET_CLOSED;
        }
        if (quote == null || priceReader.isStale(quote, now)) {
            return ErrorCode.PRICE_STALE;
        }
        if (StockOrder.BUY.equals(cmd.ordDvCd())) {
            long trdAmt = Math.multiplyExact(quote.currentPrice(), cmd.ordQty());
            return account.getDpsBal() < trdAmt ? ErrorCode.INSUFFICIENT_BALANCE : null;
        }
        long hldQty = holding == null ? 0L : holding.getHldQty();
        return hldQty < cmd.ordQty() ? ErrorCode.INSUFFICIENT_HOLDING : null;
    }

    /**
     * UNIQUE(acnt_no, idmp_key)는 최후 방어선. 락 안에서 재확인하므로 정상이라면 위반이 나지 않고,
     * 난다면 시스템 오류로 보고 그대로 던져 전부 롤백함(500).
     */
    private StockOrder saveOrder(String acntNo, OrderCommand cmd, Quote quote, String rjctRsnCd,
                                 RequestMeta meta, String trcId) {
        return orders.saveAndFlush(
                StockOrder.of(numbers.nextOrdNo(), acntNo, cmd, quote, rjctRsnCd, meta, trcId));
    }
}
