package com.example.stock_api.realtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Kafka 를 끈 경우: 체결을 바로 QuoteStore 로 보냅니다. */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "false", matchIfMissing = true)
public class DirectTickPublisher implements TickPublisher {

    private final QuoteStore hub;

    public DirectTickPublisher(QuoteStore hub) {
        this.hub = hub;
    }

    @Override
    public void publish(RealtimeMessageParser.Tick tick) {
        hub.publishTick(tick);
    }
}