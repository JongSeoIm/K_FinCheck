package com.kfincheck.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kfincheck.config.KisConfig;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "stream.enabled", havingValue = "true")
public class KisRealtimeRelay {
    private final KisConfig kis;
    private final StreamProperties properties;
    private final QuotePipeline pipeline;
    private final QuoteStore store;
    private final KisTradeParser parser;
    private final ObjectMapper mapper;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectProvider<QuoteOutboxPublisher> outbox;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile WebSocket socket;
    private volatile boolean stopped;
    private volatile Instant lastMessage = Instant.EPOCH;
    private Instant nextAttempt = Instant.EPOCH;
    private volatile String approval;
    private Instant approvalExpiry = Instant.EPOCH;
    private int failures;

    public KisRealtimeRelay(KisConfig kis, StreamProperties properties, QuotePipeline pipeline,
                            QuoteStore store, KisTradeParser parser, ObjectMapper mapper, KafkaTemplate<String, String> kafka,
                            ObjectProvider<QuoteOutboxPublisher> outbox) {
        this.kis = kis; this.properties = properties; this.pipeline = pipeline; this.store = store;
        this.parser = parser; this.mapper = mapper; this.kafka = kafka; this.outbox = outbox;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 2000)
    public synchronized void maintainConnection() {
        if (stopped || !properties.isRelayEnabled()) return;
        var durable = outbox.getIfAvailable();
        if (durable != null && !durable.canReceive()) {
            if (socket != null) socket.abort();
            socket = null; store.relayState("SPOOL_BLOCKED"); return;
        }
        if (socket != null) {
            if (Duration.between(lastMessage, Instant.now()).getSeconds() <= 90) return;
            socket.abort(); socket = null;
            store.relayState("RECONNECTING");
        }
        if (Instant.now().isBefore(nextAttempt)) return;
        store.relayState("CONNECTING");
        try {
            // With a durable spool, collection can continue while Kafka is temporarily unavailable.
            try {
                if (durable == null) kafka.partitionsFor(properties.getTopic());
            } catch (org.apache.kafka.common.KafkaException unavailable) {
                store.kafkaState("ERROR");
                store.relayState("WAITING_KAFKA");
                nextAttempt = Instant.now().plusSeconds(5);
                return;
            }
            String base = kis.getBaseUrl();
            boolean paper = "https://openapivts.koreainvestment.com:29443".equals(base);
            if (!paper && !"https://openapi.koreainvestment.com:9443".equals(base)) {
                throw new IllegalArgumentException("KIS 실전/모의 서버 주소를 확인하세요.");
            }
            if (kis.getAppKey() == null || kis.getAppKey().startsWith("YOUR_") || kis.getAppSecret() == null || kis.getAppSecret().startsWith("YOUR_")) {
                store.relayState("CONFIG_REQUIRED"); nextAttempt = Instant.now().plusSeconds(60); return;
            }
            if (approval == null || Instant.now().isAfter(approvalExpiry)) {
                SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                factory.setConnectTimeout(5000); factory.setReadTimeout(5000);
                JsonNode response = RestClient.builder().baseUrl(base).requestFactory(factory).build().post()
                        .uri("/oauth2/Approval").contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("grant_type", "client_credentials", "appkey", kis.getAppKey(), "secretkey", kis.getAppSecret()))
                        .retrieve().body(JsonNode.class);
                if (response == null || response.path("approval_key").asText().isBlank()) throw new IllegalStateException("접속키 발급 실패");
                approval = response.path("approval_key").asText();
                approvalExpiry = Instant.now().plusSeconds(23 * 3600);
            }
            URI endpoint = URI.create(paper ? "ws://ops.koreainvestment.com:31000/tryitout" : "ws://ops.koreainvestment.com:21000/tryitout");
            WebSocket opened = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(endpoint, new Listener()).get(10, TimeUnit.SECONDS);
            socket = opened;
            lastMessage = Instant.now();
            store.relayState("SUBSCRIBING");
            for (String symbol : properties.getSymbols()) {
                String request = mapper.writeValueAsString(Map.of(
                        "header", Map.of("approval_key", approval, "custtype", "P", "tr_type", "1", "content-type", "utf-8"),
                        "body", Map.of("input", Map.of("tr_id", "H0STCNT0", "tr_key", symbol))));
                opened.sendText(request, true).get(5, TimeUnit.SECONDS);
                Thread.sleep(250);
            }
            failures = 0;
        } catch (Exception e) {
            // Provider responses/exceptions can contain credentials: expose status only.
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            if (socket != null) socket.abort();
            socket = null;
            store.relayState("RECONNECTING");
            nextAttempt = Instant.now().plusSeconds(Math.min(60, 5L << Math.min(failures++, 3)));
        }
    }

    private class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();
        @Override public void onOpen(WebSocket ws) { ws.request(1); }
        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
            lastMessage = Instant.now();
            buffer.append(text);
            if (buffer.length() > 1_000_000) { ws.abort(); disconnected(ws); return null; }
            if (!last) { ws.request(1); return null; }
            String frame = buffer.toString(); buffer.setLength(0);
            try {
                if (frame.startsWith("{")) {
                    JsonNode response = mapper.readTree(frame);
                    if ("PINGPONG".equals(response.path("header").path("tr_id").asText())) {
                        ws.sendPong(ByteBuffer.wrap(frame.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    } else if (response.path("body").has("rt_cd")) {
                        if (!"0".equals(response.path("body").path("rt_cd").asText())) {
                            approval = null; ws.abort(); disconnected(ws);
                            store.relayState("SUBSCRIPTION_ERROR"); return null;
                        }
                        store.relayState("WAITING_TRADE");
                    }
                } else {
                    var durable = outbox.getIfAvailable();
                    var events = parser.parse(frame);
                    if (durable != null) durable.accept(events);
                    else for (QuoteEvent event : events) pipeline.publish(event).get(10, TimeUnit.SECONDS);
                    store.relayState("RECEIVING");
                }
            } catch (Exception e) {
                ws.abort(); disconnected(ws);
                var durable = outbox.getIfAvailable();
                store.relayState(durable != null && !durable.canReceive() ? "SPOOL_BLOCKED" : "DATA_ERROR"); return null;
            }
            ws.request(1); return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket ws, int status, String reason) { disconnected(ws); return null; }
        @Override public void onError(WebSocket ws, Throwable error) { disconnected(ws); }
        @Override public CompletionStage<?> onPing(WebSocket ws, ByteBuffer data) {
            lastMessage = Instant.now(); ws.request(1); return WebSocket.Listener.super.onPing(ws, data);
        }
        @Override public CompletionStage<?> onPong(WebSocket ws, ByteBuffer data) { lastMessage = Instant.now(); ws.request(1); return null; }
    }

    private void disconnected(WebSocket ws) {
        if (socket == ws) { socket = null; store.relayState("RECONNECTING"); }
    }

    @PreDestroy public void shutdown() {
        stopped = true;
        if (socket != null) socket.abort();
        http.close();
    }
}
