package com.kfincheck.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kfincheck.config.KisConfig;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class KisRealtimeRelayTest {
    @Test void missingKafkaIsReportedWithoutAttemptingProviderAuthentication() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        StreamProperties properties = new StreamProperties();
        QuoteStore store = new QuoteStore();
        KisConfig kis = mock(KisConfig.class);
        when(kafka.partitionsFor(properties.getTopic())).thenThrow(new KafkaException("broker unavailable"));
        var relay = new KisRealtimeRelay(kis, properties, mock(QuotePipeline.class), store,
                new KisTradeParser(), new ObjectMapper(), kafka);
        try {
            relay.maintainConnection();
            assertThat(store.snapshot(1, List.of("005930"))).containsEntry("kafka", "ERROR")
                    .containsEntry("relay", "WAITING_KAFKA");
            verifyNoInteractions(kis);
        } finally { relay.shutdown(); }
    }

    @Test void disabledRelayDoesNotContactKafkaOrKis() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        StreamProperties properties = new StreamProperties();
        properties.setRelayEnabled(false);
        KisConfig kis = mock(KisConfig.class);
        var relay = new KisRealtimeRelay(kis, properties, mock(QuotePipeline.class), new QuoteStore(),
                new KisTradeParser(), new ObjectMapper(), kafka);
        try {
            relay.maintainConnection();
            verifyNoInteractions(kafka, kis);
        } finally { relay.shutdown(); }
    }
}
