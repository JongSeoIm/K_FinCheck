package com.example.stock_api.realtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.example.stock_api.realtime.RealtimeMessageParser.Tick;

class TickFilterTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);

    private final TickFilter filter = new TickFilter();

    private static Tick tick(String code, String time, long volume) {
        return new Tick(code, time, 70000, 100, 0.14, 69000, 71000, 68000, volume);
    }

    @Test
    void acceptsNewerTick() {
        assertTrue(filter.accept(tick("005930", "090000", 100), TODAY));
        assertTrue(filter.accept(tick("005930", "090001", 200), TODAY));
    }

    @Test
    void dropsDuplicateAndOlderTicks() {
        assertTrue(filter.accept(tick("005930", "090010", 100), TODAY));
        assertFalse(filter.accept(tick("005930", "090010", 100), TODAY));
        assertFalse(filter.accept(tick("005930", "090009", 150), TODAY));
    }

    @Test
    void sameSecondWithMoreVolumeIsNewTick() {
        assertTrue(filter.accept(tick("005930", "090010", 100), TODAY));
        assertTrue(filter.accept(tick("005930", "090010", 120), TODAY));
    }

    @Test
    void newDayAndOtherStockStartFresh() {
        assertTrue(filter.accept(tick("005930", "153000", 1000), TODAY));
        assertTrue(filter.accept(tick("000660", "090000", 10), TODAY));
        assertTrue(filter.accept(tick("005930", "090000", 10), TODAY.plusDays(1)));
    }
}
