package com.kfincheck.realtime;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

@Component
public class QuoteStore {
    public static final Set<Integer> INTERVALS = Set.of(1, 5, 30, 60, 180, 300, 600, 900, 1800, 3600);
    private final Map<String, QuoteEvent> quotes = new LinkedHashMap<>();
    private final Map<String, Map<Integer, NavigableMap<Long, ObservedCandle>>> bars = new HashMap<>();
    private String relay = "DISABLED";
    private String kafka = "WAITING";

    public record ObservedCandle(String date, long open, long high, long low, long close, long volume) { }

    public synchronized void relayState(String value) { relay = value; }
    public synchronized void kafkaState(String value) { kafka = value; }

    public synchronized boolean accept(QuoteEvent next) {
        QuoteEvent old = quotes.get(next.stockCode());
        if (old != null) {
            int order = next.exchangeTime().compareTo(old.exchangeTime());
            if (order < 0 || (next.businessDate().equals(old.businessDate()) && next.volume() <= old.volume())) return false;
        }
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
            while (history.size() > 120) history.pollFirstEntry();
        }
        return true;
    }

    public synchronized Map<String, Object> snapshot(int interval, List<String> supported) {
        if (!INTERVALS.contains(interval)) throw new IllegalArgumentException("지원하지 않는 봉 주기입니다.");
        Map<String, List<ObservedCandle>> candles = new LinkedHashMap<>();
        bars.forEach((symbol, intervals) -> candles.put(symbol, List.copyOf(intervals.get(interval).values())));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "snapshot");
        boolean quiet = !quotes.isEmpty() && quotes.values().stream()
                .allMatch(q -> q.receivedAt().isBefore(Instant.now().minusSeconds(15)));
        result.put("relay", "RECEIVING".equals(relay) && quiet ? "WAITING_TRADE" : relay);
        result.put("kafka", kafka);
        result.put("supportedSymbols", supported);
        result.put("quotes", new LinkedHashMap<>(quotes));
        result.put("candles", candles);
        result.put("intervalSeconds", interval);
        result.put("serverTime", Instant.now());
        return result;
    }
}
