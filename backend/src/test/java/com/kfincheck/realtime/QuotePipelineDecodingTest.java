package com.kfincheck.realtime;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuotePipelineDecodingTest {
    @SuppressWarnings("unchecked")
    @Test void readsBothLegacyRecordsAndNewEnvelopes() throws Exception {
        var mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        var store = new QuoteStore();
        var provider = (ObjectProvider<QuotePersistenceService>) mock(ObjectProvider.class);
        var pipeline = new QuotePipeline(mock(KafkaTemplate.class), mapper, new StreamProperties(), store, provider, "test");
        pipeline.consume(new ConsumerRecord<>("quotes", 0, 0, "005930",
                mapper.writeValueAsString(QuotePersistenceRepositoryTest.message(0, 70100, 100, 10).quote())));
        pipeline.consume(new ConsumerRecord<>("quotes", 0, 1, "005930",
                mapper.writeValueAsString(QuotePersistenceRepositoryTest.message(1, 70200, 120, 20))));
        var candles = (Map<String, List<QuoteStore.ObservedCandle>>) store.snapshot(60, List.of()).get("candles");
        assertThat(candles.get("005930").getFirst().volume()).isEqualTo(30);
    }

    @SuppressWarnings("unchecked")
    @Test void databaseFailureIsPropagatedWithoutMemoryFallback() throws Exception {
        var mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        var store = new QuoteStore();
        var provider = (ObjectProvider<QuotePersistenceService>) mock(ObjectProvider.class);
        var durable = mock(QuotePersistenceService.class);
        when(provider.getIfAvailable()).thenReturn(durable);
        doThrow(new IllegalStateException("test database unavailable")).when(durable).consume(any(), any());
        var pipeline = new QuotePipeline(mock(KafkaTemplate.class), mapper, new StreamProperties(), store, provider, "test");
        var record = new ConsumerRecord<>("quotes", 0, 0, "005930",
                mapper.writeValueAsString(QuotePersistenceRepositoryTest.message(0, 70100, 100, 10)));
        assertThatThrownBy(() -> pipeline.consume(record)).isInstanceOf(IllegalStateException.class);
        assertThat((Map<?, ?>) store.snapshot(60, List.of()).get("quotes")).isEmpty();
        assertThat(store.snapshot(60, List.of()).get("kafka")).isEqualTo("ERROR");
    }

    @SuppressWarnings("unchecked")
    @Test void invalidMessageMarksErrorInsteadOfHealthyStream() {
        var store = new QuoteStore();
        var pipeline = new QuotePipeline(mock(KafkaTemplate.class), new JsonMapper(), new StreamProperties(),
                store, (ObjectProvider<QuotePersistenceService>) mock(ObjectProvider.class), "test");
        assertThatThrownBy(() -> pipeline.consume(new ConsumerRecord<>("quotes", 0, 0, "005930", "{broken")))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        assertThat(store.snapshot(60, List.of()).get("kafka")).isEqualTo("ERROR");
    }
}
