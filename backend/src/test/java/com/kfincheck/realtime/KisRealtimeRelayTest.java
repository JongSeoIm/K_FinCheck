package com.kfincheck.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kfincheck.config.KisConfig;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.beans.factory.ObjectProvider;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class KisRealtimeRelayTest {
    @SuppressWarnings("unchecked")
    @Test void durableSpoolAllowsCollectionWithoutProbingOfflineKafka() throws Exception {
        var kafka = (KafkaTemplate<String, String>) mock(KafkaTemplate.class);
        var provider = (ObjectProvider<QuoteOutboxPublisher>) mock(ObjectProvider.class);
        var publisher = mock(QuoteOutboxPublisher.class);
        when(provider.getIfAvailable()).thenReturn(publisher);
        when(publisher.canReceive()).thenReturn(true);
        KisConfig kis = mock(KisConfig.class);
        when(kis.getBaseUrl()).thenReturn("https://openapi.koreainvestment.com:9443");
        when(kis.getAppKey()).thenReturn("YOUR_APP_KEY");
        var store = new QuoteStore();
        var relay = new KisRealtimeRelay(kis, new StreamProperties(), mock(QuotePipeline.class), store,
                new KisTradeParser(), new ObjectMapper(), kafka, provider);
        try {
            relay.maintainConnection();
            verifyNoInteractions(kafka);
            assertThat(store.snapshot(60, List.of())).containsEntry("relay", "CONFIG_REQUIRED");
        } finally { relay.shutdown(); }
    }

    @SuppressWarnings("unchecked")
    @Test void blockedDiskStopsCollectionBeforeContactingKis() {
        var kafka = (KafkaTemplate<String, String>) mock(KafkaTemplate.class);
        var provider = (ObjectProvider<QuoteOutboxPublisher>) mock(ObjectProvider.class);
        var publisher = mock(QuoteOutboxPublisher.class);
        when(provider.getIfAvailable()).thenReturn(publisher);
        when(publisher.canReceive()).thenReturn(false);
        KisConfig kis = mock(KisConfig.class);
        var store = new QuoteStore();
        var relay = new KisRealtimeRelay(kis, new StreamProperties(), mock(QuotePipeline.class), store,
                new KisTradeParser(), new ObjectMapper(), kafka, provider);
        try {
            relay.maintainConnection();
            verifyNoInteractions(kis, kafka);
            assertThat(store.snapshot(60, List.of())).containsEntry("relay", "SPOOL_BLOCKED");
        } finally { relay.shutdown(); }
    }
    @Test void missingKafkaIsReportedWithoutAttemptingProviderAuthentication() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        StreamProperties properties = new StreamProperties();
        QuoteStore store = new QuoteStore();
        KisConfig kis = mock(KisConfig.class);
        when(kafka.partitionsFor(properties.getTopic())).thenThrow(new KafkaException("broker unavailable"));
        var relay = new KisRealtimeRelay(kis, properties, mock(QuotePipeline.class), store,
                new KisTradeParser(), new ObjectMapper(), kafka, mock(ObjectProvider.class));
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
                new KisTradeParser(), new ObjectMapper(), kafka, mock(ObjectProvider.class));
        try {
            relay.maintainConnection();
            verifyNoInteractions(kafka, kis);
        } finally { relay.shutdown(); }
    }
}
