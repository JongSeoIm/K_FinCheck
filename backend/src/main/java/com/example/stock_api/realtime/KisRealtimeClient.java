package com.example.stock_api.realtime;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.stock_api.kis.KisService;
import com.example.stock_api.stock.StockService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * KIS 실시간 웹소켓(H0STCNT0 체결가)에 연결해 지원 종목을 구독하고, 받은 체결을 QuoteHub 로 넘깁니다.
 * 끊기면 5초 → 최대 60초 간격으로 다시 연결합니다. JDK 내장 HttpClient WebSocket 을 써서 추가 의존성이 없습니다.
 */
@Slf4j
@Component
public class KisRealtimeClient {

    private final KisService kis;
    private final StockService stocks;
    private final QuoteHub hub;
    private final TickPublisher tickPublisher;
    private final ObjectMapper json;
    private final String wsUrl;

    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "kis-realtime");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> subscribed = ConcurrentHashMap.newKeySet();

    private volatile WebSocket socket;
    private volatile String approvalKey;
    private volatile boolean stopping = false;
    private volatile int retryDelaySec = 5;

    public KisRealtimeClient(KisService kis, StockService stocks, QuoteHub hub, TickPublisher tickPublisher, ObjectMapper json,
                             @Value("${kis.ws-url}") String wsUrl) {
        this.kis = kis;
        this.stocks = stocks;
        this.hub = hub;
        this.tickPublisher = tickPublisher;
        this.json = json;
        this.wsUrl = wsUrl;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!kis.isConfigured()) {
            log.warn("KIS 앱키가 없어 실시간 연결을 하지 않습니다.");
            return;
        }
        exec.execute(this::connect);
    }

    @PreDestroy
    public void stop() {
        stopping = true;
        WebSocket ws = socket;
        if (ws != null) ws.abort();
        exec.shutdownNow();
    }

    private void connect() {
        if (stopping) return;
        try {
            approvalKey = kis.approvalKey();
            socket = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build()
                    .newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(wsUrl + "/tryitout/" + RealtimeMessageParser.TR_ID), new Listener())
                    .get(15, TimeUnit.SECONDS);
            log.info("KIS 실시간 웹소켓 연결됨");
            retryDelaySec = 5;
            subscribed.clear();
            hub.setRealtimeConnected(true);
            syncSubscriptions();
        } catch (Exception e) {
            log.warn("KIS 실시간 연결 실패: {}", e.getMessage());
            scheduleReconnect(null);
        }
    }

    /** from: 끊긴 소켓. 이미 새 소켓으로 바뀌었으면(onError 뒤 onClose 등) 무시합니다. */
    private synchronized void scheduleReconnect(WebSocket from) {
        if (from != null && from != socket) return;
        hub.setRealtimeConnected(false);
        socket = null;
        if (stopping) return;
        int delay = retryDelaySec;
        retryDelaySec = Math.min(retryDelaySec * 2, 60);
        exec.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    /** DB 에 종목이 추가되면 1분 안에 구독도 추가합니다. (세션당 구독 수 제한 약 40종목) */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void syncSubscriptions() {
        WebSocket ws = socket;
        if (ws == null) return;
        for (String code : stocks.codes()) {
            if (subscribed.size() >= 40) break;
            if (subscribed.add(code)) send(ws, subscribeMessage(code));
        }
    }

    private String subscribeMessage(String code) {
        try {
            return json.writeValueAsString(Map.of(
                    "header", Map.of(
                            "approval_key", approvalKey,
                            "custtype", "P",
                            "tr_type", "1",
                            "content-type", "utf-8"),
                    "body", Map.of("input", Map.of(
                            "tr_id", RealtimeMessageParser.TR_ID,
                            "tr_key", code))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private synchronized void send(WebSocket ws, String text) {
        try {
            ws.sendText(text, true).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("KIS 웹소켓 전송 실패: {}", e.getMessage());
        }
    }

    private void handle(WebSocket ws, String msg) {
        if (RealtimeMessageParser.isDataMessage(msg)) {
            List<RealtimeMessageParser.Tick> ticks = RealtimeMessageParser.parse(msg);
            ticks.forEach(tickPublisher::publish);
            return;
        }
        try {
            JsonNode node = json.readTree(msg);
            String trId = node.path("header").path("tr_id").asText();
            if ("PINGPONG".equals(trId)) {
                exec.execute(() -> send(ws, msg)); // 받은 그대로 돌려줘야 연결이 유지됩니다
                return;
            }
            JsonNode body = node.path("body");
            if (!"0".equals(body.path("rt_cd").asText("0"))) {
                log.warn("KIS 실시간 응답 오류 [{}]: {}", trId, body.path("msg1").asText());
            } else {
                log.debug("KIS 실시간 응답 [{}]: {}", trId, body.path("msg1").asText());
            }
        } catch (Exception e) {
            log.debug("알 수 없는 실시간 메시지: {}", msg);
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String msg = buffer.toString();
                buffer.setLength(0);
                try {
                    handle(ws, msg);
                } catch (RuntimeException e) {
                    log.warn("실시간 메시지 처리 실패: {}", e.getMessage());
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            log.warn("KIS 실시간 연결 종료 ({}): {}", statusCode, reason);
            scheduleReconnect(ws);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.warn("KIS 실시간 오류: {}", error.getMessage());
            scheduleReconnect(ws);
        }
    }
}