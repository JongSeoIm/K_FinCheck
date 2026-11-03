package com.example.stock_api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.stock_api.common.MarketHours;
import com.example.stock_api.realtime.RealtimeMessageParser;
import com.example.stock_api.realtime.RealtimeMessageParser.Tick;

/** DB·KIS 없이 돌아가는 단위 테스트 (실시간 메시지 파서 · 장 시간) */
class StockApiApplicationTests {

    private static String record(String code, String time, String price, String sign, String diff, String rate) {
        String[] f = new String[46];
        java.util.Arrays.fill(f, "0");
        f[0] = code; f[1] = time; f[2] = price; f[3] = sign; f[4] = diff; f[5] = rate;
        f[7] = "70000"; f[8] = "72000"; f[9] = "69500"; f[13] = "1234567";
        return String.join("^", f);
    }

    @Test
    void parsesSingleTick() {
        String raw = "0|H0STCNT0|001|" + record("005930", "093015", "71500", "2", "500", "0.70");
        List<Tick> ticks = RealtimeMessageParser.parse(raw);
        assertEquals(1, ticks.size());
        Tick t = ticks.get(0);
        assertEquals("005930", t.code());
        assertEquals(71500, t.price());
        assertEquals(500, t.priceChange());
        assertEquals(71000, t.prevClose());
        assertEquals("09:30:15", t.timeText());
        assertEquals(72000, t.high());
        assertEquals(1234567, t.volume());
    }

    @Test
    void parsesFallingAndMultipleTicks() {
        String raw = "0|H0STCNT0|002|"
                + record("005930", "100000", "70000", "5", "-1000", "-1.41") + "^"
                + record("000660", "100001", "180000", "2", "2000", "1.12");
        List<Tick> ticks = RealtimeMessageParser.parse(raw);
        assertEquals(2, ticks.size());
        assertEquals(-1000, ticks.get(0).priceChange());
        assertEquals(-1.41, ticks.get(0).changeRate(), 1e-9);
        assertEquals(71000, ticks.get(0).prevClose());
        assertEquals("000660", ticks.get(1).code());
    }

    @Test
    void ignoresNonDataMessages() {
        assertTrue(RealtimeMessageParser.parse("{\"header\":{\"tr_id\":\"PINGPONG\"}}").isEmpty());
        assertTrue(RealtimeMessageParser.parse("0|H0STASP0|001|" + String.join("^", Collections.nCopies(60, "1"))).isEmpty());
        assertTrue(RealtimeMessageParser.parse("0|H0STCNT0|001|a^b").isEmpty());
        assertTrue(RealtimeMessageParser.parse(null).isEmpty());
    }

    @Test
    void marketHours() {
        assertTrue(MarketHours.isOpen(ZonedDateTime.parse("2026-11-03T09:00:00+09:00")));   // 화
        assertTrue(MarketHours.isOpen(ZonedDateTime.parse("2026-11-03T15:30:00+09:00")));
        assertFalse(MarketHours.isOpen(ZonedDateTime.parse("2026-11-03T15:31:00+09:00")));
        assertFalse(MarketHours.isOpen(ZonedDateTime.parse("2026-11-03T08:59:00+09:00")));
        assertFalse(MarketHours.isOpen(ZonedDateTime.parse("2026-11-07T10:00:00+09:00")));   // 토
        assertTrue(MarketHours.isOpen(ZonedDateTime.parse("2026-11-03T01:00:00Z")));         // = 10:00 KST
    }

    @Test
    void dartAmountParsing() throws Exception {
        var m = Class.forName("com.example.stock_api.dart.DartService").getDeclaredMethod("amount", Object.class);
        m.setAccessible(true);
        assertEquals(300870903000000L, m.invoke(null, "300,870,903,000,000"));
        assertEquals(-1234L, m.invoke(null, "-1,234"));
        assertNull(m.invoke(null, "-"));
        assertNull(m.invoke(null, ""));
    }
}
