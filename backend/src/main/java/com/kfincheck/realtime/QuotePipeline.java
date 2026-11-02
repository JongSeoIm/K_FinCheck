package com.kfincheck.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import java.util.concurrent.CompletableFuture;

@Component
@ConditionalOnProperty(name = "stream.enabled", havingValue = "true")
public class QuotePipeline {
    private final KafkaTemplate<String, String> template;
    private final ObjectMapper mapper;
    private final StreamProperties properties;
    private final QuoteStore store;

    public QuotePipeline(KafkaTemplate<String, String> template, ObjectMapper mapper, StreamProperties properties, QuoteStore store) {
        this.template = template; this.mapper = mapper; this.properties = properties; this.store = store;
    }

    public CompletableFuture<?> publish(QuoteEvent event) {
        if (!properties.getSymbols().contains(event.stockCode())) return CompletableFuture.completedFuture(null);
        try {
            return template.send(properties.getTopic(), event.stockCode(), mapper.writeValueAsString(event))
                    .whenComplete((result, error) -> { if (error != null) store.kafkaState("ERROR"); });
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("시세 직렬화 실패"));
        }
    }

    @KafkaListener(topics = "${stream.topic:stock-quotes-v1}", groupId = "${stream.group-id:k-fincheck-quotes}")
    public void consume(String payload) throws JsonProcessingException {
        QuoteEvent event = mapper.readValue(payload, QuoteEvent.class);
        if (properties.getSymbols().contains(event.stockCode())) {
            store.accept(event);
            store.kafkaState("READY");
        }
    }
}
