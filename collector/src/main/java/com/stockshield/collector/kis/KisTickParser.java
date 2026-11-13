package com.stockshield.collector.kis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.stockshield.collector.TickMessage;

/**
 * 한국투자증권 실시간 체결가(H0STCNT0) 데이터 프레임을 TickMessage로 바꾼다.
 *
 * 프레임 형식: "0|H0STCNT0|{건수}|{필드^필드^...}" (첫 글자 0 = 평문, 1 = 암호문. 체결가는 평문)
 * 한 프레임에 여러 건이 오면 필드가 건수만큼 이어 붙어 오므로, 한 건의 필드 수 = 전체 필드 수 / 건수로 나눈다.
 * 사용하는 필드(앞에서부터): 0 종목코드, 1 체결시간(HHMMSS), 2 현재가, 3 전일대비부호, 4 전일대비, 5 전일대비율,
 * 12 체결거래량, 13 누적거래량. 전일대비부호 1·2는 상승, 3은 보합, 4·5는 하락이다.
 */
public final class KisTickParser {

    public static final String TR_ID = "H0STCNT0";
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private KisTickParser() {
    }

    public static boolean isDataFrame(String frame) {
        return frame.length() > 2 && (frame.charAt(0) == '0' || frame.charAt(0) == '1') && frame.charAt(1) == '|';
    }

    public static List<TickMessage> parse(String frame, LocalDate today) {
        String[] head = frame.split("\\|", 4);
        if (head.length < 4 || !TR_ID.equals(head[1]) || !"0".equals(head[0])) {
            return List.of();
        }
        int count = Integer.parseInt(head[2]);
        String[] f = head[3].split("\\^", -1);
        if (count <= 0 || f.length % count != 0) {
            throw new IllegalArgumentException("체결가 필드 수가 건수로 나누어지지 않습니다: fields=" + f.length + ", count=" + count);
        }
        int per = f.length / count;
        List<TickMessage> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int o = i * per;
            String code = f[o];
            LocalTime time = LocalTime.parse(f[o + 1], HHMMSS);
            long price = Long.parseLong(f[o + 2]);
            int sign = signOf(f[o + 3]);
            long chg = sign * Math.abs(Long.parseLong(f[o + 4]));
            BigDecimal rate = new BigDecimal(f[o + 5]).abs();
            if (sign < 0) {
                rate = rate.negate();
            } else if (sign == 0) {
                rate = BigDecimal.ZERO.setScale(rate.scale());
            }
            long trdVol = Long.parseLong(f[o + 12]);
            long accVol = Long.parseLong(f[o + 13]);
            String ts = OffsetDateTime.of(today, time, KST).format(ISO);
            out.add(TickMessage.tick(code, price, chg, rate, trdVol, accVol, ts, "KIS"));
        }
        return out;
    }

    private static int signOf(String code) {
        return switch (code) {
            case "1", "2" -> 1;
            case "4", "5" -> -1;
            default -> 0;
        };
    }
}
