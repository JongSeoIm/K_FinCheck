package com.kfincheck.realtime;

import com.kfincheck.chat.ChatHistory;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=realtime", "stream.relay-enabled=false", "stream.persistence.enabled=true", "stream.outbox.enabled=false",
        "stream.topic=durable-quotes-test", "stream.group-id=durable-quotes-test",
        "spring.kafka.consumer.auto-offset-reset=earliest", "logging.level.root=ERROR"
})
@EmbeddedKafka(partitions = 1, topics = "durable-quotes-test", kraft = true)
@Import(DurableQuotePipelineTest.Database.class)
@DirtiesContext
class DurableQuotePipelineTest {
    @TestConfiguration static class Database {
        @Bean @Primary DataSource testDataSource() throws Exception { return QuotePersistenceRepositoryTest.database(); }
    }
    // Existing chat initialization must never access a user's local database during this test.
    @MockitoBean ChatHistory chatHistory;
    @Autowired QuotePipeline pipeline;
    @Autowired QuoteStore store;
    @Autowired QuotePersistenceService persistence;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaListenerEndpointRegistry registry;
    @Value("${spring.kafka.bootstrap-servers}") String brokers;

    @Test void failedDatabaseWriteDoesNotCommitOffsetAndRecoversWithoutDoubleCounting() throws Exception {
        await().atMost(Duration.ofSeconds(20)).until(() -> registry.getListenerContainers().stream()
                .anyMatch(c -> c.getAssignedPartitions() != null && !c.getAssignedPartitions().isEmpty()));
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", brokers))) {
            pipeline.publish(QuotePersistenceRepositoryTest.message(0, 70100, 100, 10).quote()).get(10, TimeUnit.SECONDS);
            await().ignoreException(org.springframework.dao.EmptyResultDataAccessException.class)
                    .atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                assertThat(jdbc.queryForObject("SELECT price FROM stock_quote_state", Long.class)).isEqualTo(70100);
                assertThat(committed(admin)).isEqualTo(1);
            });
            jdbc.execute("ALTER TABLE stock_quote_state ADD CONSTRAINT reject_price CHECK(price < 80000)");
            pipeline.publish(QuotePersistenceRepositoryTest.message(1, 80000, 120, 20).quote()).get(10, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(store.snapshot(60, List.of()).get("kafka")).isEqualTo("ERROR"));
            assertThat(committed(admin)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_quote_event", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT volume FROM stock_candle WHERE interval_seconds=60", Long.class)).isEqualTo(10);
            jdbc.execute("ALTER TABLE stock_quote_state DROP CONSTRAINT reject_price");
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                assertThat(jdbc.queryForObject("SELECT price FROM stock_quote_state", Long.class)).isEqualTo(80000);
                assertThat(committed(admin)).isEqualTo(2);
                assertThat(store.snapshot(60, List.of()).get("kafka")).isEqualTo("READY");
            });
            assertThat(jdbc.queryForObject("SELECT volume FROM stock_candle WHERE interval_seconds=60", Long.class)).isEqualTo(30);
            store.restorePersisted(List.of());
            persistence.restore();
            @SuppressWarnings("unchecked") var candles = (Map<String, List<QuoteStore.ObservedCandle>>) store.snapshot(60, List.of()).get("candles");
            assertThat(candles.get("005930").getFirst().volume()).isEqualTo(30);
        }
    }

    private long committed(AdminClient admin) throws Exception {
        var offsets = admin.listConsumerGroupOffsets("durable-quotes-test").partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
        var value = offsets.get(new TopicPartition("durable-quotes-test", 0));
        return value == null ? -1 : value.offset();
    }
}
