package com.kfincheck.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@Component
@ConditionalOnProperty(name = "stream.outbox.enabled", havingValue = "true")
public class QuoteOutboxPublisher implements AutoCloseable {
    private final FileQuoteOutbox outbox;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final StreamProperties properties;
    private final QuoteStore store;
    private volatile boolean diskFailed;

    public QuoteOutboxPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper,
                               StreamProperties properties, QuoteStore store,
                               @Value("${stream.outbox.directory:.realtime-spool}") String directory,
                               @Value("${stream.outbox.max-bytes:268435456}") long maxBytes,
                               @Value("${stream.outbox.max-records:100000}") int maxRecords) throws IOException {
        this.outbox = new FileQuoteOutbox(Path.of(directory), maxBytes, maxRecords, mapper);
        this.kafka = kafka; this.mapper = mapper; this.properties = properties; this.store = store;
    }

    public void accept(List<QuoteEvent> events) throws IOException {
        try {
            if (diskFailed) throw new IOException("전송 대기 저장소를 확인한 후 수집기를 재시작하세요.");
            for (QuoteEvent event : events) if (properties.getSymbols().contains(event.stockCode()))
                outbox.append(properties.getTopic(), QuoteMessage.create(event));
            updateStatus();
        } catch (IOException | RuntimeException e) {
            diskFailed = true; updateStatus(); throw e;
        }
    }

    public boolean canReceive() { return !diskFailed && outbox.hasCapacity(); }

    @Scheduled(fixedDelayString = "${stream.outbox.retry-delay-ms:1000}", initialDelay = 1000)
    public synchronized void flush() {
        for (int sent = 0; sent < 100; sent++) {
            FileQuoteOutbox.Pending next;
            try { next = outbox.first(); }
            catch (IOException | RuntimeException e) { diskFailed = true; updateStatus(); return; }
            if (next == null) { updateStatus(); return; }
            if (!properties.getTopic().equals(next.topic())
                    || !properties.getSymbols().contains(next.message().quote().stockCode())) {
                // A configuration change must not silently discard the previous collector's backlog.
                diskFailed = true; updateStatus(); return;
            }
            try {
                // Kafka delivery.timeout.ms bounds this wait. Never advance to a newer record on failure.
                kafka.send(next.topic(), next.message().quote().stockCode(), mapper.writeValueAsString(next.message())).get();
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                store.kafkaState("ERROR"); updateStatus(); return;
            }
            try { outbox.acknowledged(next); }
            catch (IOException | RuntimeException e) { diskFailed = true; updateStatus(); return; }
        }
        updateStatus();
    }

    private void updateStatus() { store.outboxState(outbox.size(), diskFailed || !outbox.hasCapacity() ? "BLOCKED" : "READY"); }
    @Override @PreDestroy public synchronized void close() throws IOException { outbox.close(); }
}
