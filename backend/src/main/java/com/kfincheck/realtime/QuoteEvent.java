package com.kfincheck.realtime;

import java.time.Instant;

public record QuoteEvent(String stockCode, long price, long priceChange, double changeRate,
                         long open, long high, long low, long volume, long tradeVolume,
                         String businessDate, String tradeTime, Instant exchangeTime,
                         Instant receivedAt, String source) { }
