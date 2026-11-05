package com.example.stock_api.realtime;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

/**
 * KIS 실시간 체결(H0STCNT0) WebSocket 을 서버가 대신 연결합니다.
 * 접속키(/oauth2/Approval)를 따로 발급해 메모리에 두고, 지정한 종목만 구독해 Kafka 로 보냅니다.
 * 접속키·앱키·원본 응답은 로그나 화면으로 내보내지 않고 상태 문구만 남깁니다.
 */
@Component
@ConditionalOnProperty(name = "stream.enabled", havingValue = "true")
public class KisRealtimeRelay {

    private static final Logger LOG = LoggerFactory.getLogger(KisRealtimeRelay.class);

    private static final String REAL_BASE = "https://openapi.koreainvestment.com:9443";
    private static final String PAPER_BASE = "https://openapivts.koreainvestment.com:29443";

    private final String appKey;
    private final String appSecret;
    private final String baseUrl;
    private final RealtimeProperties properties;
    private final QuotePipeline pipeline;
    private final RealtimeStatus status;
    private final KisTradeParser parser;
    private final ObjectMapper mapper;
    private final KafkaTemplate<String, String> kafka;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private volatile WebSocket socket;
    private volatile boolean stopped;
    private volatile Instant lastMessage = Instant.EPOCH;
    private Instant nextAttempt = Instant.EPOCH;
    private volatile String approval;
    private Instant approvalExpiry = Instant.EPOCH;
    private int failures;
    private String step = "";
    /** KIS 가 '이미 사용 중인 앱키'로 거절하면 한동안 다시 시도하지 않습니다. */
    private volatile Instant holdUntil = Instant.EPOCH;

    public KisRealtimeRelay(@Value("${kis.app-key:}") String appKey,
                            @Value("${kis.app-secret:}") String appSecret,
                            @Value("${kis.base-url:" + REAL_BASE + "}") String baseUrl,
                            RealtimeProperties properties, QuotePipeline pipeline, RealtimeStatus status,
                            KisTradeParser parser, ObjectMapper mapper, KafkaTemplate<String, String> kafka) {
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.baseUrl = baseUrl;
        this.properties = properties;
        this.pipeline = pipeline;
        this.status = status;
        this.parser = parser;
        this.mapper = mapper;
        this.kafka = kafka;
        if (!properties.isRelayEnabled()) {
            status.relay("DISABLED");
        }
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 2000)
    public synchronized void maintainConnection() {
        if (stopped || !properties.isRelayEnabled()) {
            return;
        }
        if (socket != null) {
            if (Duration.between(lastMessage, Instant.now()).getSeconds() <= 90) {
                return;
            }
            socket.abort();
            socket = null;
            status.relay("RECONNECTING");
        }
        if (Instant.now().isBefore(nextAttempt)) {
            return;
        }
        status.relay("CONNECTING");
        step = "Kafka 확인";
        try {
            // 받는 쪽 Kafka 가 준비되지 않았으면 KIS 구독을 열지 않습니다.
            try {
                kafka.partitionsFor(properties.getTopic());
            } catch (org.apache.kafka.common.KafkaException unavailable) {
                status.kafka("ERROR");
                status.relay("WAITING_KAFKA");
                nextAttempt = Instant.now().plusSeconds(5);
                return;
            }
            step = "KIS 주소 확인";
            boolean paper = PAPER_BASE.equals(baseUrl);
            if (!paper && !REAL_BASE.equals(baseUrl)) {
                throw new IllegalArgumentException("KIS 실전/모의 서버 주소를 확인하세요.");
            }
            if (appKey == null || appKey.isBlank() || appSecret == null || appSecret.isBlank()) {
                status.relay("CONFIG_REQUIRED");
                nextAttempt = Instant.now().plusSeconds(60);
                return;
            }
            if (approval == null || Instant.now().isAfter(approvalExpiry)) {
                step = "접속키 발급";
                SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                factory.setConnectTimeout(5000);
                factory.setReadTimeout(5000);
                JsonNode response = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build().post()
                        .uri("/oauth2/Approval").contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("grant_type", "client_credentials", "appkey", appKey, "secretkey", appSecret))
                        .retrieve().body(JsonNode.class);
                if (response == null || response.path("approval_key").asText().isBlank()) {
                    throw new IllegalStateException("접속키 발급 실패");
                }
                approval = response.path("approval_key").asText();
                approvalExpiry = Instant.now().plusSeconds(23 * 3600);
            }
            step = "WebSocket 연결";
            URI endpoint = URI.create(paper ? "ws://ops.koreainvestment.com:31000/tryitout"
                    : "ws://ops.koreainvestment.com:21000/tryitout");
            WebSocket opened = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(endpoint, new Listener()).get(10, TimeUnit.SECONDS);
            socket = opened;
            lastMessage = Instant.now();
            status.relay("SUBSCRIBING");
            step = "종목 구독 요청";
            final String key = approval;
            if (key == null) {
                throw new IllegalStateException("접속키 없음");
            }
            for (String symbol : properties.getSymbols()) {
                String request = mapper.writeValueAsString(Map.of(
                        "header", Map.of("approval_key", key, "custtype", "P", "tr_type", "1",
                                "content-type", "utf-8"),
                        "body", Map.of("input", Map.of("tr_id", "H0STCNT0", "tr_key", symbol))));
                opened.sendText(request, true).get(5, TimeUnit.SECONDS);
                Thread.sleep(250);
            }
            failures = 0;
        } catch (Exception e) {
            // 제공처 응답·예외에 인증 정보가 섞일 수 있어 상태만 남깁니다.
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // 어느 단계에서 실패했는지 알 수 있게 단계·예외 종류·HTTP 상태 번호만 남깁니다. (키·접속키·응답 본문은 남기지 않음)
            String code = e instanceof org.springframework.web.client.RestClientResponseException r
                    ? " HTTP " + r.getStatusCode().value() : "";
            LOG.warn("KIS 실시간 연결 실패 [{}] {}{}", step, e.getClass().getSimpleName(), code);
            if (socket != null) {
                socket.abort();
            }
            socket = null;
            if (Instant.now().isBefore(holdUntil)) {
                return; // 앱키 사용 중 거절: 상태와 대기 시간을 그대로 둡니다.
            }
            status.relay("RECONNECTING");
            nextAttempt = Instant.now().plusSeconds(Math.min(60, 5L << Math.min(failures++, 3)));
        }
    }

    private class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
            lastMessage = Instant.now();
            buffer.append(text);
            if (buffer.length() > 1_000_000) {
                ws.abort();
                disconnected(ws);
                return null;
            }
            if (!last) {
                ws.request(1);
                return null;
            }
            String frame = buffer.toString();
            buffer.setLength(0);
            try {
                if (frame.startsWith("{")) {
                    JsonNode response = mapper.readTree(frame);
                    if ("PINGPONG".equals(response.path("header").path("tr_id").asText())) {
                        ws.sendPong(ByteBuffer.wrap(frame.getBytes(StandardCharsets.UTF_8)));
                    } else if (response.path("body").has("rt_cd")) {
                        if (!"0".equals(response.path("body").path("rt_cd").asText())) {
                            String msgCd = response.path("body").path("msg_cd").asText();
                            LOG.warn("KIS 실시간 구독 거절 (rt_cd={}, msg_cd={})", response.path("body").path("rt_cd").asText(), msgCd);
                            boolean inUse = "OPSP8996".equals(msgCd);
                            if (inUse) {
                                // 같은 앱키로 이미 다른 실시간 연결이 있습니다. 1분 뒤에 다시 확인합니다.
                                holdUntil = Instant.now().plusSeconds(60);
                                nextAttempt = holdUntil;
                            } else {
                                approval = null;
                            }
                            ws.abort();
                            disconnected(ws);
                            status.relay(inUse ? "APPKEY_IN_USE" : "SUBSCRIPTION_ERROR");
                            return null;
                        }
                        status.relay("WAITING_TRADE");
                    }
                } else {
                    for (QuoteEvent event : parser.parse(frame)) {
                        pipeline.publish(event).get(10, TimeUnit.SECONDS);
                    }
                    status.relay("RECEIVING");
                }
            } catch (Exception e) {
                LOG.warn("KIS 실시간 메시지 처리 실패: {}", e.getClass().getSimpleName());
                ws.abort();
                disconnected(ws);
                status.relay("DATA_ERROR");
                return null;
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            disconnected(ws);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            disconnected(ws);
        }

        @Override
        public CompletionStage<?> onPing(WebSocket ws, ByteBuffer data) {
            lastMessage = Instant.now();
            ws.request(1);
            return WebSocket.Listener.super.onPing(ws, data);
        }

        @Override
        public CompletionStage<?> onPong(WebSocket ws, ByteBuffer data) {
            lastMessage = Instant.now();
            ws.request(1);
            return null;
        }
    }

    private void disconnected(WebSocket ws) {
        if (socket == ws) {
            socket = null;
            if (!Instant.now().isBefore(holdUntil)) {
                status.relay("RECONNECTING");
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        stopped = true;
        if (socket != null) {
            socket.abort();
        }
        http.close();
    }
}
