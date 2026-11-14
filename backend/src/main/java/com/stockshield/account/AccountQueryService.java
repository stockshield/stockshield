package com.stockshield.account;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.stockshield.account.AccountSnapshotReader.Snapshot;
import com.stockshield.common.error.ApiException;
import com.stockshield.common.error.ErrorCode;
import com.stockshield.common.web.KstTime;
import com.stockshield.common.web.PageBody;
import com.stockshield.ledger.Account;
import com.stockshield.ledger.AccountRepository;
import com.stockshield.ledger.FdsActionLog;
import com.stockshield.ledger.Holding;
import com.stockshield.ledger.PriceReader;
import com.stockshield.ledger.PriceReader.Quote;
import com.stockshield.ledger.PriceUnavailableException;
import com.stockshield.ledger.StockMasterReader;
import com.stockshield.ledger.TradeHistory;
import com.stockshield.ledger.TradeHistoryRepository;
import com.stockshield.order.StockOrder;
import com.stockshield.order.StockOrderRepository;

/**
 * 내 계좌 조회: 요약·보유 종목·주문 내역 (TRD_ACC_001 #1~#3·#5, API 명세서 'API_명세서' 18~20행).
 * 금액·현재가는 long(원), 평균 단가·비율은 BigDecimal 소수 둘째 자리 HALF_UP. double은 쓰지 않음.
 */
@Service
public class AccountQueryService {

    static final String LOCK_REASON_ADMIN = "관리자 잠금";
    private static final Logger log = LoggerFactory.getLogger(AccountQueryService.class);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ZERO_RATE = BigDecimal.ZERO.setScale(2);
    /** 같은 시각이면 나중에 채번된 번호가 먼저 */
    private static final Sort LATEST_FIRST = Sort.by(Sort.Order.desc("regTs"), Sort.Order.desc("ordNo"));

    private final AccountSnapshotReader snapshots;
    private final AccountRepository accounts;
    private final StockOrderRepository orders;
    private final TradeHistoryRepository trades;
    private final StockMasterReader stocks;
    private final PriceReader priceReader;
    private final Clock clock;

    public AccountQueryService(AccountSnapshotReader snapshots, AccountRepository accounts,
                               StockOrderRepository orders, TradeHistoryRepository trades,
                               StockMasterReader stocks, PriceReader priceReader, Clock clock) {
        this.snapshots = snapshots;
        this.accounts = accounts;
        this.orders = orders;
        this.trades = trades;
        this.stocks = stocks;
        this.priceReader = priceReader;
        this.clock = clock;
    }

    /**
     * 보유 종목 하나의 평가.
     * @param buyAmtExact 수량 × 평균 단가(반올림 전). 총 매입금액은 이 값을 더한 뒤 한 번만 반올림함
     * @param currentPrice 쓸 수 있는 시세가 없으면 null
     */
    record Valued(Holding holding, BigDecimal buyAmtExact, long buyAmt, Long currentPrice, long evalAmt,
                  boolean priceDelayed) {
        long evalPnl() {
            return evalAmt - buyAmt;
        }
    }

    public Map<String, Object> summary(long mbrNo) {
        Snapshot snapshot = snapshots.read(mbrNo);
        Account account = snapshot.account();
        List<Valued> valued = value(snapshot.holdings());

        long totalBuyAmt = valued.stream().map(Valued::buyAmtExact).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
        long totalEvalAmt = valued.stream().mapToLong(Valued::evalAmt).sum();
        long totalPnl = totalEvalAmt - totalBuyAmt;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("acntNo", account.getAcntNo());
        body.put("acntStatCd", account.getAcntStatCd());
        body.put("lockRsn", lockReason(account.getAcntStatCd()));
        body.put("dpsBal", account.getDpsBal());
        body.put("totalBuyAmt", totalBuyAmt);
        body.put("totalEvalAmt", totalEvalAmt);
        body.put("totalAssetAmt", account.getDpsBal() + totalEvalAmt);
        body.put("totalPnl", totalPnl);
        body.put("yieldRt", rate(totalPnl, totalBuyAmt));
        body.put("priceDelayed", valued.stream().anyMatch(Valued::priceDelayed));
        return body;
    }

    public List<Map<String, Object>> holdings(long mbrNo) {
        Map<String, String> names = stocks.names();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Valued v : value(snapshots.read(mbrNo).holdings())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tckCd", v.holding().getTckCd());
            item.put("tckNm", names.get(v.holding().getTckCd()));
            item.put("hldQty", v.holding().getHldQty());
            item.put("avgBuyPrc", v.holding().getAvgBuyPrc());
            item.put("currentPrice", v.currentPrice());
            item.put("evalAmt", v.evalAmt());
            item.put("evalPnl", v.evalPnl());
            item.put("pnlRt", rate(v.evalPnl(), v.buyAmt()));
            item.put("priceDelayed", v.priceDelayed());
            items.add(item);
        }
        return items;
    }

    /** @param ordStatCd FILLED·REJECTED 또는 null(전체) */
    public PageBody orders(long mbrNo, int page, int size, String ordStatCd) {
        if (ordStatCd != null && !StockOrder.FILLED.equals(ordStatCd) && !StockOrder.REJECTED.equals(ordStatCd)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        Account account = accounts.findByMbrNo(mbrNo)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        Pageable pageable = PageBody.request(page, size, LATEST_FIRST);
        Page<StockOrder> found = ordStatCd == null
                ? orders.findByAcntNo(account.getAcntNo(), pageable)
                : orders.findByAcntNoAndOrdStatCd(account.getAcntNo(), ordStatCd, pageable);

        List<String> filled = found.getContent().stream().filter(StockOrder::isFilled).map(StockOrder::getOrdNo).toList();
        Map<String, Long> trdAmts = filled.isEmpty() ? Map.of() : trades.findByOrdNoIn(filled).stream()
                .collect(Collectors.toMap(TradeHistory::getOrdNo, TradeHistory::getTrdAmt));
        Map<String, String> names = stocks.names();

        List<Map<String, Object>> content = new ArrayList<>();
        for (StockOrder o : found.getContent()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ordNo", o.getOrdNo());
            item.put("regTs", KstTime.format(o.getRegTs()));
            item.put("tckCd", o.getTckCd());
            item.put("tckNm", names.get(o.getTckCd()));
            item.put("ordDvCd", o.getOrdDvCd());
            item.put("ordQty", o.getOrdQty());
            item.put("ordPrc", o.getOrdPrc());
            item.put("trdAmt", trdAmts.get(o.getOrdNo()));
            item.put("ordStatCd", o.getOrdStatCd());
            item.put("rjctRsnCd", o.getRjctRsnCd());
            item.put("rjctRsnMsg", o.getRjctRsnCd() == null ? null : ErrorCode.valueOf(o.getRjctRsnCd()).message());
            content.add(item);
        }
        return PageBody.of(found, content);
    }

    /**
     * 시세가 60초 넘게 지났으면 그 시세로 평가하되 지연으로 표시함.
     * 쓸 수 있는 시세가 없거나 Redis를 읽을 수 없으면 조회를 막지 않고, 그 종목을 매입금액으로 평가해(손익 0) 지연으로 표시함.
     * 예수금과 계좌 상태(잠긴 이유)는 시세와 관계없이 보여야 하기 때문임.
     */
    private List<Valued> value(List<Holding> holdings) {
        LocalDateTime now = LocalDateTime.now(clock);
        boolean redisDown = false;
        List<Valued> valued = new ArrayList<>();
        for (Holding h : holdings) {
            Optional<Quote> quote = Optional.empty();
            if (!redisDown) {
                try {
                    quote = priceReader.read(h.getTckCd());
                } catch (PriceUnavailableException e) {
                    // 종목마다 타임아웃(200ms)을 기다리지 않게 나머지는 읽지 않음
                    redisDown = true;
                    log.warn("시세를 읽지 못해 매입금액으로 평가함: {}", e.getCause().toString());
                }
            }
            BigDecimal buyAmtExact = h.getAvgBuyPrc().multiply(BigDecimal.valueOf(h.getHldQty()));
            long buyAmt = buyAmtExact.setScale(0, RoundingMode.HALF_UP).longValueExact();
            if (quote.isPresent()) {
                long evalAmt = Math.multiplyExact(quote.get().currentPrice(), h.getHldQty());
                valued.add(new Valued(h, buyAmtExact, buyAmt, quote.get().currentPrice(), evalAmt,
                        priceReader.isStale(quote.get(), now)));
            } else {
                valued.add(new Valued(h, buyAmtExact, buyAmt, null, buyAmt, true));
            }
        }
        return valued;
    }

    /** 비율(%) = 손익 ÷ 매입금액 × 100. 매입금액이 0이면 0 */
    static BigDecimal rate(long pnl, long buyAmt) {
        if (buyAmt == 0) {
            return ZERO_RATE;
        }
        return BigDecimal.valueOf(pnl).multiply(HUNDRED).divide(BigDecimal.valueOf(buyAmt), 2, RoundingMode.HALF_UP);
    }

    private static String lockReason(String acntStatCd) {
        if (Account.TEMP_LOCKED.equals(acntStatCd)) {
            return FdsActionLog.TEMP_LOCK_REASON;
        }
        return Account.ACTIVE.equals(acntStatCd) ? null : LOCK_REASON_ADMIN;
    }
}
