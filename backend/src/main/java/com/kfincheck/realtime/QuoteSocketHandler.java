package com.kfincheck.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "stream.enabled", havingValue = "true")
public class QuoteSocketHandler extends TextWebSocketHandler {
    private record Connection(WebSocketSession session, int interval) { }
    private final Map<String, Connection> clients = new ConcurrentHashMap<>();
    private final QuoteStore store;
    private final ObjectMapper mapper;
    private final StreamProperties properties;

    public QuoteSocketHandler(QuoteStore store, ObjectMapper mapper, StreamProperties properties) {
        this.store = store; this.mapper = mapper; this.properties = properties;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String value = UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams().getFirst("interval");
        int interval;
        try { interval = value == null ? 1 : Integer.parseInt(value); }
        catch (NumberFormatException e) { session.close(CloseStatus.BAD_DATA); return; }
        if (!QuoteStore.INTERVALS.contains(interval) || clients.size() >= 20) { session.close(CloseStatus.POLICY_VIOLATION); return; }
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, 1000, 256 * 1024);
        clients.put(session.getId(), new Connection(safe, interval));
        send(clients.get(session.getId()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { clients.remove(session.getId()); }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable error) throws Exception {
        clients.remove(session.getId());
        if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR);
    }

    // Send a bounded snapshot instead of rerendering the browser on every execution tick.
    @Scheduled(fixedDelay = 500)
    public void broadcast() { clients.values().forEach(this::send); }

    private void send(Connection connection) {
        try {
            if (!connection.session().isOpen()) { clients.remove(connection.session().getId()); return; }
            connection.session().sendMessage(new TextMessage(mapper.writeValueAsString(store.snapshot(connection.interval(), properties.getSymbols()))));
        } catch (Exception e) {
            clients.remove(connection.session().getId());
            try { connection.session().close(CloseStatus.SERVER_ERROR); } catch (Exception ignored) { }
        }
    }
}
