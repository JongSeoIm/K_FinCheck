package com.kfincheck.realtime;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

@Component
public class QuoteStore {
    public static final Set<Integer> INTERVALS = Set.of(1, 5, 30, 60, 180, 300, 600, 900, 1800, 3600);
    public static final int MAX_CANDLES_PER_INTERVAL = 3000;
    private final Map<String, QuoteEvent> quotes = new LinkedHashMap<>();
    private final Map<String, Map<Integer, NavigableMap<Long, ObservedCandle>>> bars = new HashMap<>();
    private String relay = "DISABLED";
    private String kafka = "WAITING";
    private int pendingQuotes;
    private String outbox = "DISABLED";

    public record ObservedCandle(String date, long open, long high, long low, long close, long volume) { }

    public synchronized void relayState(String value) { relay = value; }
    public synchronized void kafkaState(String value) { kafka = value; }
    public synchronized void outboxState(int pending, String state) { pendingQuotes = pending; outbox = state; }

    public synchronized boolean accept(QuoteEvent next) {
        QuoteEvent old = quotes.get(next.stockCode());
        if (!isNewer(next, old)) return false;
        quotes.put(next.stockCode(), next);
        Map<Integer, NavigableMap<Long, ObservedCandle>> intervals = bars.computeIfAbsent(next.stockCode(), k -> new HashMap<>());
        for (int seconds : INTERVALS) {
            long bucket = Math.floorDiv(next.exchangeTime().getEpochSecond(), seconds) * seconds;
            NavigableMap<Long, ObservedCandle> history = intervals.computeIfAbsent(seconds, k -> new TreeMap<>());
            ObservedCandle bar = history.get(bucket);
            String date = Instant.ofEpochSecond(bucket).toString();
            history.put(bucket, bar == null
                    ? new ObservedCandle(date, next.price(), next.price(), next.price(), next.price(), next.tradeVolume())
                    : new ObservedCandle(date, bar.open(), Math.max(bar.high(), next.price()), Math.min(bar.low(), next.price()),
                        next.price(), bar.volume() + next.tradeVolume()));
            while (history.size() > MAX_CANDLES_PER_INTERVAL) history.pollFirstEntry();
        }
        return true;
    }

    static boolean isNewer(QuoteEvent next, QuoteEvent old) {
        return old == null || (next.exchangeTime().compareTo(old.exchangeTime()) >= 0
                && (!next.businessDate().equals(old.businessDate()) || next.volume() > old.volume()));
    }

    public synchronized void restorePersisted(List<QuotePersistenceRepository.Projection> restored) {
        quotes.clear(); bars.clear();
        restored.forEach(this::applyPersisted);
    }

    public synchronized void applyPersisted(QuotePersistenceRepository.Projection projection) {
        QuoteEvent quote = projection.quote();
        if (quote != null && isNewer(quote, quotes.get(quote.stockCode()))) quotes.put(quote.stockCode(), quote);
        for (DurableCandle candle : projection.candles()) {
            Map<Integer, NavigableMap<Long, ObservedCandle>> intervals = bars.computeIfAbsent(candle.stockCode(), k -> new HashMap<>());
            NavigableMap<Long, ObservedCandle> history = intervals.computeIfAbsent(candle.intervalSeconds(), k -> new TreeMap<>());
            history.put(candle.start().getEpochSecond(), candle.observed());
            while (history.size() > MAX_CANDLES_PER_INTERVAL) history.pollFirstEntry();
        }
    }

    public synchronized Map<String, Object> snapshot(int interval, List<String> supported) {
        if (!INTERVALS.contains(interval)) throw new IllegalArgumentException("지원하지 않는 봉 주기입니다.");
        Map<String, List<ObservedCandle>> candles = new LinkedHashMap<>();
        bars.forEach((symbol, intervals) -> candles.put(symbol,
                List.copyOf(intervals.getOrDefault(interval, Collections.emptyNavigableMap()).values())));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "snapshot");
        boolean quiet = !quotes.isEmpty() && quotes.values().stream()
                .allMatch(q -> q.receivedAt().isBefore(Instant.now().minusSeconds(15)));
        result.put("relay", "RECEIVING".equals(relay) && quiet ? "WAITING_TRADE" : relay);
        result.put("kafka", kafka);
        result.put("outbox", outbox);
        result.put("pendingQuotes", pendingQuotes);
        result.put("supportedSymbols", supported);
        result.put("quotes", new LinkedHashMap<>(quotes));
        result.put("candles", candles);
        result.put("intervalSeconds", interval);
        result.put("serverTime", Instant.now());
        return result;
    }
}
