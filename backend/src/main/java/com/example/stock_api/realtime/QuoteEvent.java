package com.example.stock_api.realtime;

import java.time.Instant;

/** KIS 실시간 체결 한 건. Kafka 토픽에는 이 값을 JSON 으로 싣습니다. */
public record QuoteEvent(String stockCode, long price, long priceChange, double changeRate,
                         long open, long high, long low, long volume, long tradeVolume,
                         String businessDate, String tradeTime, Instant exchangeTime,
                         Instant receivedAt, String source) {
}
