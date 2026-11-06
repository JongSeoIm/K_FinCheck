package com.kfincheck.realtime;

import java.time.Instant;
import java.time.LocalDate;

public record DurableCandle(String source, String stockCode, int intervalSeconds, Instant start,
                            LocalDate businessDate, long open, long high, long low, long close,
                            long volume, long tradeCount, Instant firstExchangeAt, Instant lastExchangeAt,
                            long firstCumulativeVolume, long lastCumulativeVolume, boolean closed) {
    public static final java.util.List<Integer> INTERVALS = java.util.List.of(60, 180, 300, 600, 900, 1800, 3600);

    public static Instant bucket(QuoteEvent q, int seconds) {
        return Instant.ofEpochSecond(Math.floorDiv(q.exchangeTime().getEpochSecond(), seconds) * seconds);
    }

    public static DurableCandle first(QuoteEvent q, int seconds) {
        return new DurableCandle(q.source(), q.stockCode(), seconds, bucket(q, seconds),
                LocalDate.parse(q.businessDate()), q.price(), q.price(), q.price(), q.price(), q.tradeVolume(), 1,
                q.exchangeTime(), q.exchangeTime(), q.volume(), q.volume(), false);
    }

    public DurableCandle append(QuoteEvent q) {
        return new DurableCandle(source, stockCode, intervalSeconds, start, businessDate, open,
                Math.max(high, q.price()), Math.min(low, q.price()), q.price(),
                Math.addExact(volume, q.tradeVolume()), Math.addExact(tradeCount, 1), firstExchangeAt,
                q.exchangeTime(), firstCumulativeVolume, q.volume(), closed);
    }

    public QuoteStore.ObservedCandle observed() {
        return new QuoteStore.ObservedCandle(start.toString(), open, high, low, close, volume);
    }
}
