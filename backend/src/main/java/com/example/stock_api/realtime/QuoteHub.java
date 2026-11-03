package com.example.stock_api.realtime;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.example.stock_api.common.MarketHours;
import com.example.stock_api.kis.KisService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 브라우저 WebSocket 세션과 종목별 최신 시세를 관리합니다.
 * 메시지: {"type":"quote","data":{...}} · {"type":"status",...}(15초 하트비트)
 */
@Component
public class QuoteHub {

    private static final Logger log = LoggerFactory.getLogger(QuoteHub.class);

    private final ObjectMapper json;
    private final KisService kis;

    public QuoteHub(ObjectMapper json, KisService kis) {
        this.json = json;
        this.kis = kis;
    }

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> latest = new ConcurrentHashMap<>();
    private final Map<String, Long> lastRealtimeAt = new ConcurrentHashMap<>();

    private volatile boolean realtimeConnected = false;

    // ───── 세션 ─────

    public void register(WebSocketSession session) {
        sessions.put(session.getId(), session);
        send(session, statusMessage());
        latest.values().forEach(q -> send(session, quoteMessage(q)));
    }

    public void unregister(String sessionId) {
        sessions.remove(sessionId);
    }

    public boolean hasSessions() {
        return !sessions.isEmpty();
    }

    // ───── 시세 ─────

    /** KIS 실시간 체결 수신 */
    public void publishTick(RealtimeMessageParser.Tick t) {
        Map<String, Object> q = new HashMap<>(latest.getOrDefault(t.code(), Map.of()));
        q.put("code", t.code());
        q.put("source", "realtime");
        q.put("price", t.price());
        q.put("priceChange", t.priceChange());
        q.put("change", t.changeRate());
        q.put("open", t.open());
        q.put("high", t.high());
        q.put("low", t.low());
        q.put("prevClose", t.prevClose());
        q.put("tradeTime", t.timeText());
        // 상장주수를 알고 있으면 시가총액도 체결가로 다시 계산
        if (q.get("listedShares") instanceof Number shares && shares.longValue() > 0) {
            q.put("marketCapitalization", t.price() * shares.longValue());
        }
        q.put("updatedAt", Instant.now().toString());
        lastRealtimeAt.put(t.code(), System.currentTimeMillis());
        publish(q);
    }

    /** REST 현재가 (실시간이 끊겼거나 체결이 없을 때) */
    public void publishRest(Map<String, Object> quote) {
        Map<String, Object> q = new HashMap<>(quote);
        Object code = q.get("code");
        if (code == null) return;
        // 실시간 값이 가지고 있던 체결 시각은 유지
        Map<String, Object> prev = latest.get(code.toString());
        if (prev != null && prev.get("tradeTime") != null && !q.containsKey("tradeTime")) {
            q.put("tradeTime", prev.get("tradeTime"));
        }
        publish(q);
    }

    private void publish(Map<String, Object> q) {
        latest.put(q.get("code").toString(), q);
        broadcast(quoteMessage(q));
    }

    /** 시가총액(REST 에서만 옴)을 아직 못 받은 종목인지 */
    public boolean missingMarketCap(String code) {
        Map<String, Object> q = latest.get(code);
        return q == null || q.get("marketCapitalization") == null;
    }

    public long lastRealtimeAt(String code) {
        return lastRealtimeAt.getOrDefault(code, 0L);
    }

    // ───── 상태 ─────

    public void setRealtimeConnected(boolean connected) {
        if (this.realtimeConnected != connected) {
            this.realtimeConnected = connected;
            broadcast(statusMessage());
        }
    }

    public boolean isRealtimeConnected() {
        return realtimeConnected;
    }

    @Scheduled(fixedRate = 15_000)
    public void heartbeat() {
        if (hasSessions()) broadcast(statusMessage());
    }

    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("realtime", realtimeConnected);
        s.put("kisConfigured", kis.isConfigured());
        s.put("marketOpen", MarketHours.isOpen());
        s.put("serverTime", Instant.now().toString());
        return s;
    }

    private String statusMessage() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "status");
        m.putAll(status());
        return toJson(m);
    }

    private String quoteMessage(Map<String, Object> q) {
        return toJson(Map.of("type", "quote", "data", q));
    }

    // ───── 전송 ─────

    private void broadcast(String payload) {
        sessions.values().forEach(s -> send(s, payload));
    }

    private void send(WebSocketSession session, String payload) {
        if (payload == null || !session.isOpen()) {
            if (!session.isOpen()) sessions.remove(session.getId());
            return;
        }
        try {
            session.sendMessage(new TextMessage(payload));
        } catch (IOException | IllegalStateException e) {
            log.debug("WebSocket 전송 실패 ({}): {}", session.getId(), e.getMessage());
            sessions.remove(session.getId());
        }
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            log.warn("JSON 변환 실패: {}", e.getMessage());
            return null;
        }
    }
}