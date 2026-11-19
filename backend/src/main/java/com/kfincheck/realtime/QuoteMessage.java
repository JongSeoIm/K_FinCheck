package com.kfincheck.realtime;

import java.util.UUID;

/** The ID belongs to the event, not its Kafka delivery attempt. */
public record QuoteMessage(String eventId, QuoteEvent quote) {
    public static QuoteMessage create(QuoteEvent quote) {
        return new QuoteMessage(UUID.randomUUID().toString(), quote);
    }

    public QuoteMessage {
        if (eventId == null || !UUID.fromString(eventId).toString().equals(eventId) || quote == null)
            throw new IllegalArgumentException("시세 이벤트 형식 오류");
    }
}
