package com.stockshield.order;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

import com.stockshield.common.error.ErrorCode;
import com.stockshield.common.error.ErrorResponse;
import com.stockshield.common.web.KstTime;
import com.stockshield.ledger.TradeHistory;

/**
 * 주문 응답. 처음 응답과 같은 키 재요청 응답을 같은 코드로, 저장된 행(orders·trade_history)만으로 만들어
 * 둘이 달라질 수 없게 함 (API 명세서 '공통 규칙' 46행, 요구사항명세서 부록 #16).
 *
 * @param replayed 같은 키 재요청이면 true (응답 헤더 Idempotency-Replayed)
 */
public record OrderReply(HttpStatus status, Object body, boolean replayed) {

    /** @param trade 체결이면 그 주문의 체결 내역, 거절이면 null */
    static OrderReply of(StockOrder order, TradeHistory trade, boolean replayed) {
        if (!order.isFilled()) {
            ErrorCode code = ErrorCode.valueOf(order.getRjctRsnCd());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("ordNo", order.getOrdNo());
            detail.put("ordStatCd", order.getOrdStatCd());
            // 본문 traceId는 결과를 처음 만든 요청의 번호 (SYS_SEC_001 #6)
            return new OrderReply(code.status(), ErrorResponse.of(code, detail, order.getTrcId()), replayed);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ordNo", order.getOrdNo());
        body.put("tckCd", order.getTckCd());
        body.put("ordDvCd", order.getOrdDvCd());
        body.put("ordQty", order.getOrdQty());
        body.put("ordPrc", order.getOrdPrc());
        body.put("trdAmt", trade.getTrdAmt());
        body.put("ordStatCd", order.getOrdStatCd());
        body.put("dpsBal", trade.getPostDpsBal());
        body.put("hldQty", trade.getPostHldQty());
        body.put("regTs", KstTime.format(order.getRegTs()));
        return new OrderReply(HttpStatus.CREATED, body, replayed);
    }
}
