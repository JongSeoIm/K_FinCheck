package com.example.stock_api.realtime;

import java.util.ArrayList;
import java.util.List;

/**
 * KIS 실시간 체결가(H0STCNT0) 메시지 파서.
 * 형식: "0|H0STCNT0|건수|필드^필드^..." — 한 건은 46개 필드이며, 여러 건이면 이어 붙어 옵니다.
 * 필드 순서: 0 종목코드, 1 체결시간(HHmmss), 2 현재가, 3 전일대비부호, 4 전일대비, 5 전일대비율,
 *            6 가중평균가, 7 시가, 8 고가, 9 저가, ..., 13 누적거래량
 */
public final class RealtimeMessageParser {

    public static final String TR_ID = "H0STCNT0";
    private static final int MIN_FIELDS = 14;

    private RealtimeMessageParser() {}

    public record Tick(String code, String time, long price, long priceChange, double changeRate,
                       long open, long high, long low, long volume) {

        /** 전일 종가 = 현재가 - 전일 대비 */
        public long prevClose() {
            return price - priceChange;
        }

        /** "HH:mm:ss" */
        public String timeText() {
            return time.length() == 6
                    ? time.substring(0, 2) + ":" + time.substring(2, 4) + ":" + time.substring(4, 6)
                    : time;
        }
    }

    public static boolean isDataMessage(String raw) {
        return raw != null && (raw.startsWith("0|") || raw.startsWith("1|"));
    }

    public static List<Tick> parse(String raw) {
        List<Tick> ticks = new ArrayList<>();
        if (!isDataMessage(raw)) return ticks;

        String[] head = raw.split("\\|", 4);
        if (head.length < 4 || !TR_ID.equals(head[1]) || "1".equals(head[0])) return ticks; // 1 = 암호화 (체결가는 평문)

        int count;
        try {
            count = Math.max(1, Integer.parseInt(head[2].trim()));
        } catch (NumberFormatException e) {
            count = 1;
        }

        String[] f = head[3].split("\\^", -1);
        int per = f.length / count;
        if (per < MIN_FIELDS) return ticks;

        for (int i = 0; i < count; i++) {
            int b = i * per;
            String sign = f[b + 3];
            boolean down = "4".equals(sign) || "5".equals(sign);
            long diff = Math.abs(toLong(f[b + 4]));
            double rate = Math.abs(toDouble(f[b + 5]));
            ticks.add(new Tick(
                    f[b].trim(),
                    f[b + 1].trim(),
                    toLong(f[b + 2]),
                    down ? -diff : diff,
                    down ? -rate : rate,
                    toLong(f[b + 7]),
                    toLong(f[b + 8]),
                    toLong(f[b + 9]),
                    toLong(f[b + 13])));
        }
        return ticks;
    }

    private static long toLong(String v) {
        try {
            return Long.parseLong(v.trim());
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static double toDouble(String v) {
        try {
            return Double.parseDouble(v.trim());
        } catch (RuntimeException e) {
            return 0.0;
        }
    }
}
