package com.stockshield.order;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.stockshield.common.error.ApiException;
import com.stockshield.common.error.ErrorCode;
import com.stockshield.common.trace.TraceIds;
import com.stockshield.fds.api.RequestMeta;
import com.stockshield.ledger.Account;
import com.stockshield.ledger.AccountRepository;
import com.stockshield.ledger.IdempotencyKeys;
import com.stockshield.ledger.PriceReader;
import com.stockshield.ledger.PriceReader.Quote;
import com.stockshield.ledger.PriceUnavailableException;
import com.stockshield.ledger.StockMasterReader;

/**
 * 주문 접수: 입력 검증 → 멱등키 선조회 → 종목 확인 → 시세 읽기 → 원장 트랜잭션(OrderLedger).
 * 이 클래스에는 @Transactional을 붙이지 않음. 시세(Redis)를 DB 트랜잭션·계좌 락 밖에서 읽기 위해서임 (TRD_ORD_001 #2·구현방안).
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    static final long MAX_ORD_QTY = 1_000_000L;

    private final AccountRepository accounts;
    private final StockOrderRepository orders;
    private final StockMasterReader stocks;
    private final PriceReader priceReader;
    private final OrderLedger orderLedger;

    public OrderService(AccountRepository accounts, StockOrderRepository orders, StockMasterReader stocks,
                        PriceReader priceReader, OrderLedger orderLedger) {
        this.accounts = accounts;
        this.orders = orders;
        this.stocks = stocks;
        this.priceReader = priceReader;
        this.orderLedger = orderLedger;
    }

    public OrderReply place(long mbrNo, OrderController.OrderRequest request, RequestMeta meta) {
        OrderCommand cmd = validate(request);
        Account account = accounts.findByMbrNo(mbrNo)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));

        // 이미 처리된 키는 시세도 락도 없이 처음 결과. Redis 장애 중에도 재요청은 답을 받음 (SYS_CORE_002 #5)
        Optional<StockOrder> existing = orders.findByAcntNoAndIdmpKey(account.getAcntNo(), cmd.idmpKey());
        if (existing.isPresent()) {
            return orderLedger.replay(existing.get(), cmd);
        }
        if (!stocks.exists(cmd.tckCd())) {
            throw new ApiException(ErrorCode.STOCK_NOT_FOUND);
        }
        Quote quote;
        try {
            quote = priceReader.read(cmd.tckCd()).orElse(null);
        } catch (PriceUnavailableException e) {
            log.warn("시세를 읽지 못해 주문을 받지 않음 tckCd={}: {}", cmd.tckCd(), e.getCause().toString());
            throw new ApiException(ErrorCode.PRICE_UNAVAILABLE);
        }
        return orderLedger.place(mbrNo, cmd, quote, meta, TraceIds.current());
    }

    /** 화면 검증과 관계없이 서버에서 다시 검증. 틀리면 바꿔 주지 않고 400 (SYS_SEC_001 #5) */
    private static OrderCommand validate(OrderController.OrderRequest r) {
        if (r == null || r.tckCd() == null || r.tckCd().isBlank() || r.ordQty() == null
                || r.ordQty() < 1 || r.ordQty() > MAX_ORD_QTY
                || !(StockOrder.BUY.equals(r.ordDvCd()) || StockOrder.SELL.equals(r.ordDvCd()))) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        return new OrderCommand(r.tckCd(), r.ordDvCd(), r.ordQty(), IdempotencyKeys.normalize(r.idmpKey()));
    }
}
