package com.kfincheck.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Component
@ConditionalOnProperty(name = "stream.enabled", havingValue = "true")
public class QuotePipeline {
    private final KafkaTemplate<String, String> template;
    private final ObjectMapper mapper;
    private final StreamProperties properties;
    private final QuoteStore store;
    private final ObjectProvider<QuotePersistenceService> persistence;
    private final String group;

    public QuotePipeline(KafkaTemplate<String, String> template, ObjectMapper mapper, StreamProperties properties, QuoteStore store,
                         ObjectProvider<QuotePersistenceService> persistence,
                         @Value("${stream.group-id:k-fincheck-quotes}") String group) {
        this.template = template; this.mapper = mapper; this.properties = properties; this.store = store;
        this.persistence = persistence; this.group = group;
    }

    public CompletableFuture<?> publish(QuoteEvent event) {
        if (!properties.getSymbols().contains(event.stockCode())) return CompletableFuture.completedFuture(null);
        try {
            return template.send(properties.getTopic(), event.stockCode(), mapper.writeValueAsString(QuoteMessage.create(event)))
                    .whenComplete((result, error) -> { if (error != null) store.kafkaState("ERROR"); });
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("시세 직렬화 실패"));
        }
    }

    @KafkaListener(topics = "${stream.topic:stock-quotes-v1}", groupId = "${stream.group-id:k-fincheck-quotes}")
    public void consume(ConsumerRecord<String, String> record) throws JsonProcessingException {
        try {
            String payload = record.value();
            var tree = mapper.readTree(payload);
            // Existing Kafka records keep a deterministic ID on replay; new records carry their own ID.
            QuoteMessage message = tree.has("eventId") ? mapper.treeToValue(tree, QuoteMessage.class)
                    : new QuoteMessage(UUID.nameUUIDFromBytes(payload.getBytes(StandardCharsets.UTF_8)).toString(),
                        mapper.treeToValue(tree, QuoteEvent.class));
            QuoteEvent event = message.quote();
            if (properties.getSymbols().contains(event.stockCode())) {
                var durable = persistence.getIfAvailable();
                if (durable == null) store.accept(event);
                else durable.consume(message, new QuotePersistenceRepository.Position(group, record.topic(), record.partition(), record.offset()));
                store.kafkaState("READY");
            }
        } catch (JsonProcessingException | RuntimeException e) {
            store.kafkaState("ERROR");
            throw e;
        }
    }
}
