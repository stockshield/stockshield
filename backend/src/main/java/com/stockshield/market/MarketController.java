package com.stockshield.market;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시세·종목 조회 API (API 명세서 'API_명세서' 10~13행, 인가 None). 담당은 윤제정(연결 합의서 1절).
 * 오류는 공통 에러 형식(common/error)으로 응답한다: 없는 종목 404 STOCK_NOT_FOUND, Redis 연결 실패 503.
 */
@RestController
@RequestMapping("/api/v1/market")
public class MarketController {

    private final MarketQueryService service;
    private final OrderbookService orderbook;

    public MarketController(MarketQueryService service, OrderbookService orderbook) {
        this.service = service;
        this.orderbook = orderbook;
    }

    /** 종목 자동완성 검색 (10행) */
    @GetMapping("/stocks/search")
    public List<Map<String, Object>> search(@RequestParam(name = "keyword", required = false) String keyword) {
        return service.search(keyword);
    }

    /** 메인 대시보드: 지수, 상승률·하락률·거래량 상위 10, 위험 의심 종목, 시세 지연 여부 (11행) */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return service.summary();
    }

    /** 종목 상세: 현재가, 가장 최근 거래일 1분봉, 위험 의심 여부 (12행) */
    @GetMapping("/stocks/{tckCd}")
    public Map<String, Object> detail(@PathVariable String tckCd) {
        return service.detail(tckCd);
    }

    /** 종목 호가 10단계 (차트·호가창 합의 5절). 화면이 3초마다 부른다. KIS 실패면 503 ORDERBOOK_UNAVAILABLE */
    @GetMapping("/stocks/{tckCd}/orderbook")
    public Map<String, Object> orderbook(@PathVariable String tckCd) {
        return orderbook.orderbook(tckCd);
    }

    /** 위험 의심 종목 목록. 시세가 60초 넘게 끊기면 빈 목록 (13행, FDS_MKT_001 #4) */
    @GetMapping("/risk-stocks")
    public List<Map<String, Object>> riskStocks() {
        return service.riskStocks();
    }
}
