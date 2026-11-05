package com.example.stock_api.realtime;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.example.stock_api.quote.QuoteStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 체결 → Kafka(생산) → 소비 → QuoteStore.
 * 소비하는 쪽에서 오래된 체결을 거르고, 화면의 Polling·Push 가 읽는 QuoteStore 에 넣습니다.
 * 체결마다 초·분봉용 샘플도 함께 쌓습니다(실제 체결 기준).
 */
@Component
@ConditionalOnProperty(name = "stream.enabled", havingValue = "true")
public class QuotePipeline {

    private static final Logger LOG = LoggerFactory.getLogger(QuotePipeline.class);

    private final KafkaTemplate<String, String> template;
    private final ObjectMapper mapper;
    private final RealtimeProperties properties;
    private final QuoteStore store;
    private final RealtimeStatus status;
    private final TickFilter filter = new TickFilter();

    public QuotePipeline(KafkaTemplate<String, String> template, ObjectMapper mapper,
                         RealtimeProperties properties, QuoteStore store, RealtimeStatus status) {
        this.template = template;
        this.mapper = mapper;
        this.properties = properties;
        this.store = store;
        this.status = status;
    }

    public CompletableFuture<?> publish(QuoteEvent event) {
        if (!properties.getSymbols().contains(event.stockCode())) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            return template.send(properties.getTopic(), event.stockCode(), mapper.writeValueAsString(event))
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            status.kafka("ERROR");
                        }
                    });
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("시세 직렬화 실패"));
        }
    }

    @KafkaListener(topics = "${stream.topic:stock-quotes-v1}", groupId = "${spring.kafka.consumer.group-id:k-fincheck-quotes}")
    public void consume(String payload) {
        try {
            QuoteEvent event = mapper.readValue(payload, QuoteEvent.class);
            if (!properties.getSymbols().contains(event.stockCode()) || !filter.accept(event)) {
                return;
            }
            long now = System.currentTimeMillis();
            QuoteStore.Quote quote = new QuoteStore.Quote(event.stockCode(), event.price(), event.priceChange(),
                    event.changeRate(), event.open(), event.high(), event.low(),
                    event.price() - event.priceChange(), event.volume(),
                    Instant.ofEpochMilli(now).toString(), store.nextSeq());
            store.put(quote, new QuoteStore.Sample(now, event.price(), event.volume()));
            status.tick(event.stockCode(), now);
            status.kafka("READY");
        } catch (Exception e) {
            // 체결 한 건이 잘못돼도 소비가 멈추지 않게 합니다. 값은 남기지 않고 종류만 기록합니다.
            LOG.warn("실시간 체결 처리 실패: {}", e.getClass().getSimpleName());
        }
    }
}
