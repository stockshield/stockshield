package com.stockshield.fds.risk;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * 한 종목의 1분 수익률 통계 (MKT_TICK_002 #2, FDS_MKT_001 #1).
 *
 * 체결이 올 때마다 그 분의 마지막 가격(종가)을 갱신하고, 다음 분의 체결이 오면 끝난 분의 1분 수익률
 * r = 종가 / 직전 분 종가 − 1을 만든다. Z-Score는 이번 수익률을 넣기 전의 최근 30개 수익률 평균·표준편차로 구한다.
 * 체결이 없는 분은 건너뛴다(직전에 체결이 있었던 분의 종가와 비교). 한 스레드(시세 컨슈머)에서만 바꾼다.
 */
final class ReturnWindow {

    static final int WINDOW = 30;

    private final Deque<Double> returns = new ArrayDeque<>(WINDOW);
    private String minute;          // 진행 중인 분 (yyyyMMddHHmm)
    private long minuteClose;       // 진행 중인 분의 마지막 가격
    private Long prevClose;         // 직전에 끝난 분의 종가

    /** 끝난 분의 수익률과, 그 수익률을 넣기 전 창의 통계 */
    record MinuteReturn(String minute, double ret, int n, double mean, double std) {
        Optional<Double> z() {
            if (n < WINDOW || std <= 0) {
                return Optional.empty();
            }
            return Optional.of((ret - mean) / std);
        }
    }

    /** 체결 하나를 넣는다. 분이 바뀌어 끝난 분의 수익률이 생기면 돌려준다 */
    Optional<MinuteReturn> add(String bsMi, long price) {
        if (minute == null) {
            minute = bsMi;
            minuteClose = price;
            return Optional.empty();
        }
        if (bsMi.compareTo(minute) < 0) {
            return Optional.empty();    // 늦게 도착한 지난 분의 체결은 무시
        }
        if (bsMi.equals(minute)) {
            minuteClose = price;
            return Optional.empty();
        }
        Optional<MinuteReturn> done = Optional.empty();
        if (prevClose != null && prevClose > 0) {
            double r = (double) minuteClose / prevClose - 1.0;
            done = Optional.of(stats(minute, r));
            returns.addLast(r);
            if (returns.size() > WINDOW) {
                returns.removeFirst();
            }
        }
        prevClose = minuteClose;
        minute = bsMi;
        minuteClose = price;
        return done;
    }

    int size() {
        return returns.size();
    }

    private MinuteReturn stats(String m, double r) {
        int n = returns.size();
        if (n < 2) {
            return new MinuteReturn(m, r, n, 0, 0);
        }
        double sum = 0;
        for (double x : returns) {
            sum += x;
        }
        double mean = sum / n;
        double sq = 0;
        for (double x : returns) {
            sq += (x - mean) * (x - mean);
        }
        return new MinuteReturn(m, r, n, mean, Math.sqrt(sq / (n - 1)));
    }
}
