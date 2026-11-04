package com.example.stock_api.realtime;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.example.stock_api.realtime.RealtimeMessageParser.Tick;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * KIS 체결 → Kafka → QuoteHub 경로.
 * 전송이 실패하면 시세가 끊기지 않도록 바로 QuoteHub 로 보내고, 10초 동안은 Kafka 를 건너뜁니다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class QuotePipeline implements TickPublisher {

    private static final long RETRY_AFTER_MS = 10_000;

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final QuoteHub hub;
    private final String topic;
    private volatile long skipUntil = 0;

    public QuotePipeline(KafkaTemplate<String, String> kafka, ObjectMapper json, QuoteHub hub,
                         @Value("${app.kafka.topic}") String topic) {
        this.kafka = kafka;
        this.json = json;
        this.hub = hub;
        this.topic = topic;
    }

    /** 실시간 시세 토픽. 없으면 시작할 때 만듭니다. */
    @Bean
    static NewTopic stockQuotesTopic(@Value("${app.kafka.topic}") String topic) {
        return TopicBuilder.name(topic).partitions(1).replicas(1).build();
    }

    /** 보내기: 체결을 공통 형식으로 바꿔 종목 코드 키로 Kafka 에 넣습니다. */
    @Override
    public void publish(Tick tick) {
        if (System.currentTimeMillis() < skipUntil) {
            hub.publishTick(tick);
            return;
        }
        try {
            kafka.send(topic, tick.code(), json.writeValueAsString(QuoteEvent.from(tick)))
                    .whenComplete((result, error) -> {
                        if (error != null) fallback(tick, error);
                    });
        } catch (JsonProcessingException | RuntimeException e) {
            fallback(tick, e);
        }
    }

    /** 받기: Kafka 에서 꺼낸 시세를 QuoteHub 로 넘깁니다. 이후 화면 전달은 기존과 같습니다. */
    @KafkaListener(topics = "${app.kafka.topic}")
    public void receive(String message) {
        try {
            hub.publishTick(json.readValue(message, QuoteEvent.class).toTick());
        } catch (JsonProcessingException e) {
            log.warn("Kafka 시세 메시지를 읽지 못했습니다: {}", e.getMessage());
        }
    }

    private void fallback(Tick tick, Throwable error) {
        skipUntil = System.currentTimeMillis() + RETRY_AFTER_MS;
        log.warn("Kafka 전송 실패, 10초 동안 시세를 바로 전달합니다: {}", error.getMessage());
        hub.publishTick(tick);
    }
}