package com.stockshield.fds.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.stockshield.fds.risk.ReturnWindow.MinuteReturn;

/** 위험 의심 근거 문장 (화면에 그대로 보여 주는 값, 한승희 수정 요청 11/18) */
class StockRiskReasonTest {

    private static final LocalDateTime TS = LocalDateTime.of(2026, 11, 18, 10, 1);

    /** 표본 30개, 평균 0, 표준편차 0.0005인 분에서 ret만큼 움직임 → Z = ret / 0.0005 (-0.26%면 Z=-5.2) */
    private static MinuteReturn minute(double ret) {
        return new MinuteReturn("202611181000", ret, 30, 0.0, 0.0005);
    }

    @Test
    @DisplayName("1분 급변만 맞으면 '1분 새 -0.26%, 평소 움직임보다 이례적으로 큼'이고 Z 값은 보이지 않는다")
    void zOnly() {
        StockRiskJudge.Risk r = StockRiskJudge.judge("005930", minute(-0.0026), new BigDecimal("-1.20"), TS).orElseThrow();
        assertThat(r.dir()).isEqualTo("DOWN");
        assertThat(r.reason()).isEqualTo("1분 새 -0.26%, 평소 움직임보다 이례적으로 큼");
    }

    @Test
    @DisplayName("전일 대비 ±10%만 맞으면 '전일 대비 -13.94%'")
    void rateOnly() {
        StockRiskJudge.Risk r = StockRiskJudge.judge("005930", minute(0.0), new BigDecimal("-13.94"), TS).orElseThrow();
        assertThat(r.dir()).isEqualTo("DOWN");
        assertThat(r.reason()).isEqualTo("전일 대비 -13.94%");
    }

    @Test
    @DisplayName("둘 다 맞으면 두 근거를 ' · '로 잇는다")
    void both() {
        StockRiskJudge.Risk r = StockRiskJudge.judge("005930", minute(0.0052), new BigDecimal("12.30"), TS).orElseThrow();
        assertThat(r.dir()).isEqualTo("UP");
        assertThat(r.reason()).isEqualTo("1분 새 +0.52%, 평소 움직임보다 이례적으로 큼 · 전일 대비 +12.30%");
    }
}
