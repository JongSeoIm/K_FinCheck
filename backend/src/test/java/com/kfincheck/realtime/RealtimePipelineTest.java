package com.kfincheck.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=realtime", "stream.relay-enabled=false", "stream.topic=quotes-integration-test",
        "stream.group-id=quotes-integration-test", "spring.kafka.consumer.auto-offset-reset=earliest"
})
@EmbeddedKafka(partitions = 1, topics = "quotes-integration-test", kraft = true)
@DirtiesContext
class RealtimePipelineTest {
    @LocalServerPort int port;
    @Autowired QuotePipeline pipeline;
    @Autowired KisTradeParser parser;
    @Autowired ObjectMapper mapper;
    @Autowired KafkaListenerEndpointRegistry registry;

    @Test void parsedTradeTravelsThroughRealKafkaAndReachesPushAndPolling() throws Exception {
        await().atMost(Duration.ofSeconds(20)).until(() -> registry.getListenerContainers().stream()
                .anyMatch(c -> c.getAssignedPartitions() != null && !c.getAssignedPartitions().isEmpty()));
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        try (HttpClient client = HttpClient.newHttpClient()) {
            WebSocket socket = client.newWebSocketBuilder().header("Origin", "http://localhost:3000")
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws/quotes?interval=60"), new WebSocket.Listener() {
                        private final StringBuilder text = new StringBuilder();
                        @Override public void onOpen(WebSocket ws) { ws.request(1); }
                        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                            text.append(data);
                            if (last) { messages.add(text.toString()); text.setLength(0); }
                            ws.request(1); return null;
                        }
                    }).get(5, TimeUnit.SECONDS);
            try {
                QuoteEvent event = parser.parse("0|H0STCNT0|001|" + KisTradeParserTest.row("143000", "70100", "2", "100", "10")).getFirst();
                pipeline.publish(event).get(10, TimeUnit.SECONDS);
                JsonNode received = null;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (System.nanoTime() < deadline) {
                    String message = messages.poll(1, TimeUnit.SECONDS);
                    if (message == null) continue;
                    JsonNode candidate = mapper.readTree(message);
                    if (candidate.path("quotes").has("005930")) { received = candidate; break; }
                }
                assertThat(received).isNotNull();
                assertThat(received.path("quotes").path("005930").path("price").asLong()).isEqualTo(70100);
                assertThat(received.path("candles").path("005930").get(0).path("close").asLong()).isEqualTo(70100);
                JsonNode polled = RestClient.create("http://localhost:" + port).get()
                        .uri("/api/realtime/snapshot?interval=60").retrieve().body(JsonNode.class);
                assertThat(polled.path("quotes")).isEqualTo(received.path("quotes"));
                assertThat(polled.path("kafka").asText()).isEqualTo("READY");
            } finally { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(3, TimeUnit.SECONDS); }
        }
    }
}
