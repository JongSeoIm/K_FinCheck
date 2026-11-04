package com.example.stock_api.realtime;

import java.time.Instant;

import com.example.stock_api.realtime.RealtimeMessageParser.Tick;

/** Kafka 토픽에 넣는 공통 시세 메시지. symbol 은 앞의 0 을 지키기 위해 문자열입니다. */
public record QuoteEvent(String symbol, String tradeTime, long price, long priceChange, double changeRate,
                         long open, long high, long low, long cumulativeVolume, String receivedAt, String source) {

    public static QuoteEvent from(Tick tick) {
        return new QuoteEvent(tick.code(), tick.time(), tick.price(), tick.priceChange(), tick.changeRate(),
                tick.open(), tick.high(), tick.low(), tick.volume(), Instant.now().toString(), "KIS");
    }

    public Tick toTick() {
        return new Tick(symbol, tradeTime, price, priceChange, changeRate, open, high, low, cumulativeVolume);
    }
}