package com.kfincheck.realtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "stream.persistence.enabled", havingValue = "true")
public class QuotePersistenceService {
    private final QuotePersistenceRepository repository;
    private final QuoteStore store;
    private final StreamProperties properties;
    private final String source;

    public QuotePersistenceService(QuotePersistenceRepository repository, QuoteStore store, StreamProperties properties,
                                   @Value("${stream.persistence.source:KIS_WEBSOCKET_KRX}") String source) {
        this.repository = repository; this.store = store; this.properties = properties; this.source = source;
    }

    @EventListener(ApplicationStartedEvent.class)
    public synchronized void restore() {
        var restored = repository.load(source, properties.getSymbols());
        store.restorePersisted(restored);
    }

    public synchronized void consume(QuoteMessage message, QuotePersistenceRepository.Position position) {
        if (!source.equals(message.quote().source())) throw new IllegalArgumentException("영속 시세 출처가 다릅니다.");
        // save() returns only after the database transaction commits. No memory mutation on a failed commit.
        var projection = repository.save(message, position);
        store.applyPersisted(projection);
    }
}
