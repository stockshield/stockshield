package com.stockshield.news;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 기사 제목·요약에 나온 종목명·종목 코드로 관련 종목을 찾는다 (NEWS_COL_001 "종목 연결").
 *
 * finlight 무료 플랜은 기사별 기업 정보를 주지 않아서 글자로 찾는다.
 * - 이름 바로 뒤에 영문·숫자나 조사가 아닌 한글이 이어지면 다른 회사로 본다. 예: "SK"는 "SK증권"에,
 *   "현대차"는 "현대차증권"에 걸리지 않는다. "삼성전자가"처럼 조사가 붙은 경우는 연결한다.
 *   긴 이름부터 찾고, 찾은 자리는 지워 짧은 이름("SK")이 긴 이름("SK하이닉스") 안에서 다시 걸리지 않게 한다.
 * - 자주 쓰는 다른 이름(네이버, 하이닉스, LG엔솔 등)도 같은 종목으로 본다.
 * - "[표]"로 시작하는 시세표 기사는 종목명만 나열하므로 연결하지 않는다.
 * - 한 기사가 너무 많은 종목에 걸리면(시장 전체 기사) 연결하지 않는다.
 */
public final class StockMatcher {

    static final int MAX_STOCKS_PER_ARTICLE = 5;

    private static final Map<String, List<String>> ALIASES = Map.of(
            "035420", List.of("네이버"),
            "000660", List.of("하이닉스"),
            "373220", List.of("LG엔솔"),
            "012450", List.of("한화에어로"),
            "207940", List.of("삼성바이오"),
            "005380", List.of("현대자동차"),
            "196170", List.of("알테오젠"),
            "034020", List.of("두산에너빌"));

    private record Name(String text, String tckCd) {
    }

    private final List<Name> names;

    /** @param stocks 종목 코드 → 종목명 (stock_master) */
    public StockMatcher(Map<String, String> stocks) {
        List<Name> all = new ArrayList<>();
        stocks.forEach((code, name) -> {
            all.add(new Name(name, code));
            all.add(new Name(code, code));
            ALIASES.getOrDefault(code, List.of()).forEach(a -> all.add(new Name(a, code)));
        });
        all.sort(Comparator.comparingInt((Name n) -> n.text().length()).reversed());
        this.names = List.copyOf(all);
    }

    public Set<String> match(String title, String summary) {
        String t = title == null ? "" : title.strip();
        if (t.startsWith("[표]")) {
            return Set.of();
        }
        StringBuilder text = new StringBuilder(t).append('\n').append(summary == null ? "" : summary);
        Set<String> found = new LinkedHashSet<>();
        for (Name n : names) {
            int from = 0;
            int at;
            while ((at = indexOf(text, n.text(), from)) >= 0) {
                int end = at + n.text().length();
                if (!continuesWord(text, end) && !startsInsideWord(text, at, n.text())) {
                    found.add(n.tckCd());
                    for (int i = at; i < end; i++) {
                        text.setCharAt(i, ' ');
                    }
                }
                from = end;
            }
        }
        return found.size() > MAX_STOCKS_PER_ARTICLE ? Set.of() : found;
    }

    private static int indexOf(StringBuilder text, String s, int from) {
        return text.indexOf(s, from);
    }

    /** 이름 뒤에 바로 붙어도 같은 종목으로 보는 조사·말 (삼성전자가, SK하이닉스의, 현대차·기아 측 …) */
    private static final String PARTICLES = "은는이가을를의와과도로에만측보까부처株주";

    /**
     * 이름 바로 뒤가 영문·숫자거나 조사가 아닌 한글이면 더 긴 다른 이름의 일부다
     * (SK증권, 현대차증권, 삼성전자우 등은 다른 종목)
     */
    private static boolean continuesWord(StringBuilder text, int end) {
        if (end >= text.length()) {
            return false;
        }
        char c = text.charAt(end);
        if (isAsciiWord(c)) {
            return true;
        }
        return isWordChar(c) && PARTICLES.indexOf(c) < 0;
    }

    /** 영문·숫자 이름(SK, HLB, 종목 코드)은 앞에도 영문·숫자가 붙어 있으면 다른 낱말이다 */
    private static boolean startsInsideWord(StringBuilder text, int at, String name) {
        if (at == 0 || !isAsciiWord(name.charAt(0))) {
            return false;
        }
        return isAsciiWord(text.charAt(at - 1));
    }

    private static boolean isWordChar(char c) {
        return isAsciiWord(c) || (c >= '가' && c <= '힣');
    }

    private static boolean isAsciiWord(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }
}
