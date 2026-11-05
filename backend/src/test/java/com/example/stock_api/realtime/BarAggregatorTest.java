package com.example.stock_api.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.stock_api.realtime.BarAggregator.Bar;
import com.example.stock_api.realtime.RealtimeMessageParser.Tick;

class BarAggregatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    private static final int MINUTE = 60;

    private final BarAggregator aggregator = new BarAggregator();

    private static Tick tick(String time, long price, long volume) {
        return new Tick("005930", time, price, 0, 0, 0, 0, 0, volume);
    }

    @Test
    void ticksInSameMinuteMakeOneBar() {
        aggregator.apply(tick("090010", 100, 1000), TODAY);
        aggregator.apply(tick("090020", 120, 1500), TODAY);
        aggregator.apply(tick("090030", 90, 1700), TODAY);
        aggregator.apply(tick("090040", 110, 1800), TODAY);

        List<Bar> bars = aggregator.bars("005930", MINUTE);
        assertEquals(List.of(new Bar(MINUTE, "09:00:00", 100, 120, 90, 110, 800)), bars);
    }

    @Test
    void noBarIsMadeForEmptyMinutes() {
        aggregator.apply(tick("090010", 100, 1000), TODAY);
        aggregator.apply(tick("090510", 110, 1100), TODAY);
        assertEquals(2, aggregator.bars("005930", MINUTE).size());
    }

    @Test
    void returnsChangedBarForEveryInterval() {
        assertEquals(BarAggregator.INTERVALS.size(), aggregator.apply(tick("090010", 100, 1000), TODAY).size());
    }

    @Test
    void newDayClearsBars() {
        aggregator.apply(tick("090010", 100, 1000), TODAY);
        aggregator.apply(tick("090010", 200, 10), TODAY.plusDays(1));
        List<Bar> bars = aggregator.bars("005930", MINUTE);
        assertEquals(1, bars.size());
        assertEquals(200, bars.get(0).open());
    }

    @Test
    void unknownStockHasNoBars() {
        assertTrue(aggregator.bars("000660", MINUTE).isEmpty());
    }
}
