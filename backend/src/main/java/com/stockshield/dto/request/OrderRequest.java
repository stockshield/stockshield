package com.stockshield.dto.request;

public record OrderRequest(String tckCd, String ordDvCd, Long ordQty, String idmpKey) {
}

