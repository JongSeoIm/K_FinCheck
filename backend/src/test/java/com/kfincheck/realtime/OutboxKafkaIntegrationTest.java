package com.kfincheck.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kfincheck.chat.ChatHistory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=realtime", "stream.relay-enabled=false", "stream.persistence.enabled=true",
        "stream.outbox.enabled=false", "stream.topic=outbox-integration-test", "stream.group-id=outbox-integration-test",
        "spring.kafka.consumer.auto-offset-reset=earliest", "logging.level.root=ERROR"
})
@EmbeddedKafka(partitions = 1, topics = "outbox-integration-test", kraft = true)
@Import(DurableQuotePipelineTest.Database.class)
@DirtiesContext
class OutboxKafkaIntegrationTest {
    @TempDir Path directory;
    @MockitoBean ChatHistory chatHistory;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper mapper;
    @Autowired StreamProperties properties;
    @Autowired QuoteStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaListenerEndpointRegistry registry;

    @Test void diskBacklogAfterCollectorRestartTravelsThroughKafkaToDatabase() throws Exception {
        await().atMost(Duration.ofSeconds(20)).until(() -> registry.getListenerContainers().stream()
                .anyMatch(c -> c.getAssignedPartitions() != null && !c.getAssignedPartitions().isEmpty()));
        var first = QuotePersistenceRepositoryTest.message(0, 70000, 100, 10).quote();
        var second = QuotePersistenceRepositoryTest.message(1, 70100, 120, 20).quote();
        // Close before sending, as if a collector stopped with pending work.
        try (var publisher = new QuoteOutboxPublisher(kafka, mapper, properties, store, directory.toString(), 100000, 10)) {
            publisher.accept(List.of(first, second));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_quote_state", Integer.class)).isZero();
        }
        try (var publisher = new QuoteOutboxPublisher(kafka, mapper, properties, store, directory.toString(), 100000, 10)) {
            publisher.flush();
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_quote_event", Integer.class)).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT volume FROM stock_candle WHERE interval_seconds=60", Long.class)).isEqualTo(30);
            });
            assertThat(store.snapshot(60, List.of())).containsEntry("pendingQuotes", 0);
        }
    }
}
