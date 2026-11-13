package com.stockshield.fds.risk;

/**
 * 위험 의심 종목이 생기거나 풀렸다는 사실. StockRiskJudge가 발행하고, 시세 화면 전송(MarketBroadcaster)이
 * GET /market/risk-stocks 응답 전체를 /topic/risk로 보낸다 (연결 합의서 v0.0.9 회신 8번).
 */
public record RiskStocksChanged(String tckCd, boolean riskYn) {
}
