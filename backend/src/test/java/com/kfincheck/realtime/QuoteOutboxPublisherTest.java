package com.kfincheck.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.mockito.ArgumentCaptor;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuoteOutboxPublisherTest {
    @TempDir Path directory;
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    @SuppressWarnings("unchecked") KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    QuoteStore store = new QuoteStore();
    QuoteOutboxPublisher open() throws Exception {
        return new QuoteOutboxPublisher(kafka, mapper, new StreamProperties(), store, directory.toString(), 100000, 10);
    }

    @Test void changedTopicBlocksSendingWithoutDiscardingOldBacklog() throws Exception {
        try (var publisher = open()) {
            publisher.accept(List.of(QuotePersistenceRepositoryTest.message(0, 70000, 100, 10).quote()));
        }
        var properties = new StreamProperties(); properties.setTopic("different-topic");
        try (var publisher = new QuoteOutboxPublisher(kafka, mapper, properties, store, directory.toString(), 100000, 10)) {
            publisher.flush();
            verifyNoInteractions(kafka);
            assertThat(store.snapshot(60, List.of())).containsEntry("outbox", "BLOCKED").containsEntry("pendingQuotes", 1);
        }
        try (var spool = new FileQuoteOutbox(directory, 100000, 10, mapper)) { assertThat(spool.size()).isEqualTo(1); }
    }

    @Test void kafkaFailureKeepsBacklogAndRestartResendsSameIdInOrder() throws Exception {
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        var first = QuotePersistenceRepositoryTest.message(0, 70000, 100, 10).quote();
        var second = QuotePersistenceRepositoryTest.message(1, 70100, 120, 20).quote();
        try (var publisher = open()) {
            publisher.accept(List.of(first, second));
            verifyNoInteractions(kafka); // Receipt is durable before any send.
            publisher.flush();
            assertThat(store.snapshot(60, List.of())).containsEntry("pendingQuotes", 2).containsEntry("kafka", "ERROR");
        }
        var values = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(anyString(), anyString(), values.capture());
        String original = values.getValue();
        reset(kafka);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        try (var publisher = open()) {
            publisher.flush();
            verify(kafka, times(2)).send(anyString(), anyString(), values.capture());
            assertThat(values.getAllValues().get(1)).isEqualTo(original);
            assertThat(mapper.readValue(values.getAllValues().get(2), QuoteMessage.class).quote()).isEqualTo(second);
            assertThat(store.snapshot(60, List.of())).containsEntry("pendingQuotes", 0);
        }
        try (var spool = new FileQuoteOutbox(directory, 100000, 10, mapper)) { assertThat(spool.size()).isZero(); }
    }

    @Test void brokerAckWithoutLocalDeletionCanBeReplayedWithoutDoubleAggregation() throws Exception {
        // Simulate the crash window: broker accepted the first message but the collector never observed success.
        var message = QuotePersistenceRepositoryTest.message(0, 70000, 100, 10);
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(QuotePersistenceRepositoryTest.database());
        var repo = new QuotePersistenceRepository(jdbc,
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()), mapper);
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(call -> {
            var envelope = mapper.readValue((String)call.getArgument(2), QuoteMessage.class);
            repo.save(envelope, new QuotePersistenceRepository.Position("test", "quotes", 0, 0));
            return CompletableFuture.failedFuture(new IllegalStateException("ack lost"));
        });
        try (var publisher = open()) { publisher.accept(List.of(message.quote())); publisher.flush(); }
        doAnswer(call -> {
            var envelope = mapper.readValue((String)call.getArgument(2), QuoteMessage.class);
            repo.save(envelope, new QuotePersistenceRepository.Position("test", "quotes", 0, 1));
            return CompletableFuture.<SendResult<String, String>>completedFuture(null);
        }).when(kafka).send(anyString(), anyString(), anyString());
        try (var publisher = open()) { publisher.flush(); }
        assertThat(jdbc.queryForObject("SELECT volume FROM stock_candle WHERE interval_seconds=60", Long.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_quote_event", Integer.class)).isEqualTo(1);
    }
}
