package com.stockshield.order;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stockshield.auth.AuthPrincipal;
import com.stockshield.common.web.RequestMetas;

import jakarta.servlet.http.HttpServletRequest;

/** 주문 API (API 명세서 'API_명세서' 17행). ROLE_USER만 들어옴(SecurityConfig) */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    static final String REPLAYED_HEADER = "Idempotency-Replayed";

    public record OrderRequest(String tckCd, String ordDvCd, Long ordQty, String idmpKey) {
    }

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /** 체결 201, 저장된 거절 422·423. 같은 키 재요청이면 처음과 같은 상태·본문에 헤더만 붙음 */
    @PostMapping
    public ResponseEntity<Object> place(@AuthenticationPrincipal AuthPrincipal principal,
                                        @RequestBody OrderRequest body, HttpServletRequest request) {
        OrderReply reply = orderService.place(principal.mbrNo(), body, RequestMetas.optionalDevice(request));
        ResponseEntity.BodyBuilder response = ResponseEntity.status(reply.status());
        if (reply.replayed()) {
            response.header(REPLAYED_HEADER, "true");
        }
        return response.body(reply.body());
    }
}
