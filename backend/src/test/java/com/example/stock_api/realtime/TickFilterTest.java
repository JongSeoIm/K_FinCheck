package com.example.stock_api.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class TickFilterTest {

    private static QuoteEvent event(String date, String time, long volume) {
        Instant at = Instant.parse(date + "T" + time + "Z");
        return new QuoteEvent("005930", 219500, -3800, -1.70, 221000, 222000, 219000, volume, 10,
                date, time, at, at, "KIS_WEBSOCKET_KRX");
    }

    @Test
    void 새_체결만_받는다() {
        TickFilter filter = new TickFilter();
        assertTrue(filter.accept(event("2026-11-05", "00:00:01", 100)));
        assertFalse(filter.accept(event("2026-11-05", "00:00:01", 100)), "같은 누적거래량은 중복");
        assertFalse(filter.accept(event("2026-11-05", "00:00:00", 120)), "더 이른 시각은 역순");
        assertFalse(filter.accept(event("2026-11-05", "00:00:02", 90)), "누적거래량이 줄면 거절");
        assertTrue(filter.accept(event("2026-11-05", "00:00:02", 130)));
    }

    @Test
    void 날짜가_바뀌면_누적거래량이_다시_시작해도_받는다() {
        TickFilter filter = new TickFilter();
        assertTrue(filter.accept(event("2026-11-05", "06:30:00", 5_000_000)));
        assertTrue(filter.accept(event("2026-11-06", "00:00:01", 10)));
    }

    @Test
    void 파서는_부호와_이전_종가_계산에_필요한_값을_해석한다() {
        String[] f = new String[46];
        java.util.Arrays.fill(f, "0");
        f[0] = "005930"; f[1] = "090001"; f[2] = "219500"; f[3] = "5"; f[4] = "3800"; f[5] = "1.70";
        f[7] = "221000"; f[8] = "222000"; f[9] = "219000"; f[12] = "15"; f[13] = "1234567"; f[33] = "20261105";
        List<QuoteEvent> events = new KisTradeParser().parse("0|H0STCNT0|001|" + String.join("^", f));
        assertEquals(1, events.size());
        QuoteEvent e = events.get(0);
        assertEquals(-3800, e.priceChange());
        assertEquals(223300, e.price() - e.priceChange());
        assertEquals(1234567, e.volume());
        assertEquals("2026-11-05", e.businessDate());
    }

    @Test
    void 파서는_다른_메시지를_무시한다() {
        assertTrue(new KisTradeParser().parse("1|H0STCNI0|001|abc").isEmpty());
    }
}
