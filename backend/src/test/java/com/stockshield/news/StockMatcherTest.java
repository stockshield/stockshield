package com.stockshield.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StockMatcherTest {

    private static final Map<String, String> STOCKS = new LinkedHashMap<>();

    static {
        STOCKS.put("005930", "삼성전자");
        STOCKS.put("000660", "SK하이닉스");
        STOCKS.put("402340", "SK스퀘어");
        STOCKS.put("034730", "SK");
        STOCKS.put("005380", "현대차");
        STOCKS.put("000270", "기아");
        STOCKS.put("035420", "NAVER");
        STOCKS.put("028300", "HLB");
        STOCKS.put("006400", "삼성SDI");
    }

    private final StockMatcher matcher = new StockMatcher(STOCKS);

    @Test
    @DisplayName("조사가 붙은 종목명과 괄호 안 종목 코드를 찾는다")
    void namesWithParticlesAndCodes() {
        assertThat(matcher.match("삼성전자가 미국 공장 증설", null)).containsExactly("005930");
        assertThat(matcher.match("반도체 수출 호조", "SK하이닉스의 HBM 매출이 늘었다")).containsExactly("000660");
        assertThat(matcher.match("오늘의 특징주", "삼성전자(005930)와 기아는 강세")).containsExactlyInAnyOrder("005930", "000270");
    }

    @Test
    @DisplayName("짧은 이름(SK)은 긴 이름(SK하이닉스·SK스퀘어) 안에서 다시 걸리지 않고, 따로 나오면 걸린다")
    void shortNameInsideLongName() {
        assertThat(matcher.match("SK하이닉스·SK스퀘어 동반 상승", null)).containsExactlyInAnyOrder("000660", "402340");
        assertThat(matcher.match("SK는 자회사 지분을 정리한다", null)).containsExactly("034730");
    }

    @Test
    @DisplayName("다른 회사 이름의 일부는 연결하지 않는다 (SK증권, 현대차증권, 삼성SDI가 삼성에 걸리지 않음)")
    void otherCompanies() {
        assertThat(matcher.match("SK증권, 현대차증권 리포트 발간", null)).isEmpty();
        assertThat(matcher.match("삼성SDI 배터리 수주", null)).containsExactly("006400");
        assertThat(matcher.match("HLBIO 신약", null)).isEmpty();
    }

    @Test
    @DisplayName("다른 이름(네이버)도 같은 종목으로 본다")
    void aliases() {
        assertThat(matcher.match("네이버, AI 검색 개편", null)).containsExactly("035420");
    }

    @Test
    @DisplayName("[표] 시세표 기사와 종목이 너무 많은 시장 전체 기사는 연결하지 않는다")
    void tablesAndMarketWide() {
        assertThat(matcher.match("[표] 코스피 외국인 순매수 상위 종목", "삼성전자 SK하이닉스")).isEmpty();
        assertThat(matcher.match("코스피 마감", "삼성전자, SK하이닉스, SK스퀘어, 현대차, 기아, NAVER가 올랐다")).isEmpty();
    }
}
