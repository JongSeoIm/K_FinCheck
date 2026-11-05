package com.example.stock_api.realtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 실시간 체결 수신 상태. 실시간 기능을 끄고 실행해도 항상 존재하며(OFF),
 * 수집기는 이 값으로 "최근에 체결이 들어온 종목"을 건너뜁니다.
 */
@org.springframework.stereotype.Component
public class RealtimeStatus {

    private volatile String relay = "OFF";
    private volatile String kafka = "OFF";
    private final Map<String, Long> lastTick = new ConcurrentHashMap<>();

    public void relay(String state) {
        this.relay = state;
    }

    public void kafka(String state) {
        this.kafka = state;
    }

    public void tick(String code, long atMillis) {
        lastTick.put(code, atMillis);
    }

    public boolean recentTick(String code, long withinMillis) {
        Long at = lastTick.get(code);
        return at != null && System.currentTimeMillis() - at <= withinMillis;
    }

    public Map<String, Object> view(List<String> symbols) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("relay", relay);
        body.put("kafka", kafka);
        body.put("symbols", symbols);
        Map<String, Object> ticks = new LinkedHashMap<>();
        for (String code : symbols) {
            Long at = lastTick.get(code);
            ticks.put(code, at == null ? null : java.time.Instant.ofEpochMilli(at).toString());
        }
        body.put("lastTick", ticks);
        return body;
    }
}
