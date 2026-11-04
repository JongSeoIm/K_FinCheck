package com.example.stock_api.websocket;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.example.stock_api.quote.QuoteStore;
import com.example.stock_api.quote.SupportedStocks;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Push 시세 전달: 연결된 화면에 0.5초마다 QuoteStore 의 최신 시세를 보냅니다.
 * - 접속하면 지원 종목 전체를 받고, {"type":"subscribe","codes":["005930"]} 로 바꿀 수 있습니다.
 * - 지원하지 않는 종목 코드와 너무 큰 메시지는 무시합니다.
 */
@Component
public class StockWebSocketHandler extends TextWebSocketHandler {

    private static final Logger LOG = LoggerFactory.getLogger(StockWebSocketHandler.class);
    private static final int MAX_CLIENT_MESSAGE = 1024;

    private record Client(WebSocketSession session, Set<String> codes) {}

    private final QuoteStore store;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Client> clients = new ConcurrentHashMap<>();

    public StockWebSocketHandler(QuoteStore store) {
        this.store = store;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 한 세션에 동시에 보내다 꼬이지 않도록 감싸 줍니다. (5초 이상 막히면 끊음)
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, 5000, 64 * 1024);
        clients.put(session.getId(), new Client(safe, new CopyOnWriteArraySet<>(SupportedStocks.CODES)));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Client client = clients.get(session.getId());
        if (client == null || message.getPayloadLength() > MAX_CLIENT_MESSAGE) {
            return;
        }
        try {
            JsonNode node = mapper.readTree(message.getPayload());
            if (!"subscribe".equals(node.path("type").asText()) || !node.path("codes").isArray()) {
                return;
            }
            Set<String> next = new CopyOnWriteArraySet<>();
            for (JsonNode code : node.path("codes")) {
                if (SupportedStocks.contains(code.asText())) {
                    next.add(code.asText());
                }
            }
            client.codes().clear();
            client.codes().addAll(next);
        } catch (Exception ignored) {
            // 형식이 틀린 메시지는 무시
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        clients.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        clients.remove(session.getId());
    }

    @Scheduled(fixedRate = 500)
    public void broadcast() {
        if (clients.isEmpty()) {
            return;
        }
        String serverTime = Instant.now().toString();
        for (Client client : clients.values()) {
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("type", "quotes");
                body.put("serverTime", serverTime);
                body.put("quotes", store.snapshot(List.copyOf(client.codes())));
                client.session().sendMessage(new TextMessage(mapper.writeValueAsString(body)));
            } catch (Exception e) {
                clients.remove(client.session().getId());
                try {
                    client.session().close();
                } catch (Exception ignored) {
                    // 이미 끊긴 연결
                }
                LOG.debug("WebSocket 전송 실패로 연결 정리: {}", e.getClass().getSimpleName());
            }
        }
    }
}
