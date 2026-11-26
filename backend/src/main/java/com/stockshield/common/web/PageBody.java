package com.stockshield.common.web;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.stockshield.common.error.ApiException;
import com.stockshield.common.error.ErrorCode;

/** 공통 페이징 형식. page는 0부터, size는 1~100 (API 명세서 '공통 규칙' 56행) */
public record PageBody(List<?> content, int page, int size, long totalElements, int totalPages) {

    static final int MAX_SIZE = 100;

    public static PageBody of(Page<?> page, List<?> content) {
        return new PageBody(content, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    /** 범위를 벗어나면 고쳐 주지 않고 400 */
    public static Pageable request(int page, int size, Sort sort) {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        return PageRequest.of(page, size, sort);
    }
}
