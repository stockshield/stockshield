package com.stockshield.collector.kis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.stockshield.collector.TickMessage;

class KisTickParserTest {

    static final LocalDate DAY = LocalDate.of(2026, 11, 13);

    /** 46개 필드 중 쓰는 필드만 채운 한 건 */
    static String record(String code, String hhmmss, String price, String sign, String chg, String rate,
                         String trdVol, String accVol) {
        String[] f = Collections.nCopies(46, "0").toArray(new String[0]);
        f[0] = code;
        f[1] = hhmmss;
        f[2] = price;
        f[3] = sign;
        f[4] = chg;
        f[5] = rate;
        f[12] = trdVol;
        f[13] = accVol;
        return String.join("^", f);
    }

    @Test
    @DisplayName("체결가 한 건: 하락 부호면 전일 대비와 등락률이 음수, 시세 시각은 오늘 날짜 + 체결 시간(+09:00)")
    void parsesSingleRecord() {
        String frame = "0|H0STCNT0|001|" + record("005930", "093354", "207500", "5", "500", "0.24", "12", "6126832");
        List<TickMessage> ticks = KisTickParser.parse(frame, DAY);
        assertThat(ticks).hasSize(1);
        TickMessage t = ticks.get(0);
        assertThat(t.code()).isEqualTo("005930");
        assertThat(t.currentPrice()).isEqualTo(207_500L);
        assertThat(t.chgPrc()).isEqualTo(-500L);
        assertThat(t.chgRt()).isEqualByComparingTo(new BigDecimal("-0.24"));
        assertThat(t.trdVol()).isEqualTo(12L);
        assertThat(t.accVol()).isEqualTo(6_126_832L);
        assertThat(t.prcTs()).isEqualTo("2026-11-13T09:33:54+09:00");
        assertThat(t.src()).isEqualTo("KIS");
        assertThat(t.type()).isEqualTo("TICK");
    }

    @Test
    @DisplayName("한 프레임에 여러 건이 오면 필드 수를 건수로 나눠 각각 만든다. 상승 부호는 양수, 보합은 0")
    void parsesMultipleRecords() {
        String body = record("000660", "100000", "1241000", "2", "13000", "1.06", "3", "100")
                + "^" + record("000660", "100001", "1254000", "3", "0", "0.00", "4", "104");
        List<TickMessage> ticks = KisTickParser.parse("0|H0STCNT0|002|" + body, DAY);
        assertThat(ticks).extracting(TickMessage::chgPrc).containsExactly(13_000L, 0L);
        assertThat(ticks.get(0).chgRt()).isEqualByComparingTo("1.06");
        assertThat(ticks.get(1).prcTs()).isEqualTo("2026-11-13T10:00:01+09:00");
    }

    @Test
    @DisplayName("다른 TR, 암호문 프레임은 무시하고, 필드 수가 맞지 않으면 예외")
    void ignoresOtherFramesAndRejectsBrokenOnes() {
        assertThat(KisTickParser.parse("0|H0STASP0|001|a^b", DAY)).isEmpty();
        assertThat(KisTickParser.parse("1|H0STCNT0|001|encrypted", DAY)).isEmpty();
        assertThat(KisTickParser.isDataFrame("{\"header\":{}}")).isFalse();
        assertThatThrownBy(() -> KisTickParser.parse("0|H0STCNT0|002|a^b^c", DAY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 지수(H0UPCNT0) 한 건: 30개 필드 중 쓰는 필드만 채움 */
    static String indexRecord(String code, String hhmmss, String value, String sign, String chg, String accVol,
                              String rate) {
        String[] f = Collections.nCopies(30, "0").toArray(new String[0]);
        f[0] = code;
        f[1] = hhmmss;
        f[2] = value;
        f[3] = sign;
        f[4] = chg;
        f[5] = accVol;
        f[9] = rate;
        return String.join("^", f);
    }

    @Test
    @DisplayName("지수 한 건: type INDEX, 지수·전일 대비는 소수, 하락 부호면 음수, currentPrice는 비움")
    void parsesIndexRecord() {
        String frame = "0|H0UPCNT0|001|" + indexRecord("0001", "091502", "2650.31", "5", "11.07", "123456", "0.42");
        List<TickMessage> ticks = KisTickParser.parse(frame, DAY);
        assertThat(ticks).hasSize(1);
        TickMessage t = ticks.get(0);
        assertThat(t.type()).isEqualTo("INDEX");
        assertThat(t.code()).isEqualTo("0001");
        assertThat(t.idxVal()).isEqualByComparingTo("2650.31");
        assertThat(t.idxChg()).isEqualByComparingTo("-11.07");
        assertThat(t.chgRt()).isEqualByComparingTo("-0.42");
        assertThat(t.accVol()).isEqualTo(123_456L);
        assertThat(t.currentPrice()).isNull();
        assertThat(t.chgPrc()).isNull();
        assertThat(t.prcTs()).isEqualTo("2026-11-13T09:15:02+09:00");
    }

    @Test
    @DisplayName("지수 여러 건(코스피·코스닥)도 필드 수를 건수로 나눠 각각 만든다. 상승은 양수")
    void parsesMultipleIndexRecords() {
        String body = indexRecord("0001", "100000", "2655.00", "2", "4.69", "1", "0.18")
                + "^" + indexRecord("1001", "100000", "870.12", "3", "0.00", "2", "0.00");
        List<TickMessage> ticks = KisTickParser.parse("0|H0UPCNT0|002|" + body, DAY);
        assertThat(ticks).extracting(TickMessage::code).containsExactly("0001", "1001");
        assertThat(ticks.get(0).idxChg()).isEqualByComparingTo("4.69");
        assertThat(ticks.get(1).idxChg()).isEqualByComparingTo("0");
    }
}
