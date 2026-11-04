package com.example.stock_api.realtime;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 브라우저 WebSocket 세션과 종목별 최신 시세를 관리합니다.
 * 메시지: {"type":"quote","data":{...}} · {"type":"status",...}(15초 하트비트)
 * 시세와 봉은 0.5초마다 모아서 보냅니다. 그 사이에 바뀐 값만, 가장 최신 값 1건씩 보냅니다.
 * 봉 메시지: {"type":"bars","code":"005930","bars":[...]}
 */
@Component
public class QuoteHub {

    private static final Logger log = LoggerFactory.getLogger(QuoteHub.class);
    private static final long PUSH_INTERVAL_MS = 500;

    private final ObjectMapper json;
    private final KisService kis;

    public QuoteHub(ObjectMapper json, KisService kis) {
        this.json = json;
        this.kis = kis;
    }

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> latest = new ConcurrentHashMap<>();
    private final Map<String, Long> lastRealtimeAt = new ConcurrentHashMap<>();
    private final TickFilter tickFilter = new TickFilter();
    private final BarAggregator barAggregator = new BarAggregator();
    private final Map<String, Map<Integer, BarAggregator.Bar>> changedBars = new ConcurrentHashMap<>(); // 마지막 전송 후 바뀐 봉
    private final Set<String> changed = ConcurrentHashMap.newKeySet(); // 마지막 전송 후 시세가 바뀐 종목

    // 0.5초 전송 전용 스레드 (KIS 조회 같은 다른 예약 작업이 오래 걸려도 전송이 밀리지 않게 따로 둡니다)
    private final ScheduledExecutorService pusher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "quote-push");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    void startPush() {
        pusher.scheduleAtFixedRate(this::flush, PUSH_INTERVAL_MS, PUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stopPush() {
        pusher.shutdownNow();
    }

    /** 0.5초마다: 바뀐 종목의 최신 시세를 브라우저에 보냅니다 */
    private void flush() {
        try {
            for (String code : Set.copyOf(changed)) {
                changed.remove(code);
                Map<String, Object> q = latest.get(code);
                if (q != null && hasSessions()) broadcast(quoteMessage(q));
            }
            for (String code : Set.copyOf(changedBars.keySet())) {
                Map<Integer, BarAggregator.Bar> bars = changedBars.remove(code);
                if (bars != null && hasSessions()) broadcast(barsMessage(code, List.copyOf(bars.values())));
            }
        } catch (RuntimeException e) {
            log.warn("시세 전송 실패: {}", e.getMessage()); // 예외가 나도 다음 0.5초 전송은 계속
        }
    }

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
        if (!tickFilter.accept(t, LocalDate.now(MarketHours.KST))) return; // 중복·역순 체결은 버림
        Map<Integer, BarAggregator.Bar> bars = changedBars.computeIfAbsent(t.code(), code -> new ConcurrentHashMap<>());
        barAggregator.apply(t, LocalDate.now(MarketHours.KST)).forEach(bar -> bars.put(bar.interval(), bar));
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
        if (t.volume() > 0) q.put("volume", t.volume()); // 누적거래량
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
        String code = q.get("code").toString();
        latest.put(code, q);
        changed.add(code); // 바로 보내지 않고 0.5초 전송 때 모아서 보냄
    }

    /** Polling 응답용: 서버가 이미 받아 둔 최신 시세 (없으면 null). Polling 마다 KIS 를 부르지 않기 위해 씁니다. */
    public Map<String, Object> latest(String code) {
        return latest.get(code);
    }

    /** 서버가 집계한 초·분봉 (Polling 과 화면 첫 조회용) */
    public List<BarAggregator.Bar> bars(String code, int interval) {
        return barAggregator.bars(code, interval);
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

    private String barsMessage(String code, List<BarAggregator.Bar> bars) {
        return toJson(Map.of("type", "bars", "code", code, "bars", bars));
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