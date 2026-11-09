package com.kfincheck.realtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/realtime")
@ConditionalOnProperty(name = "stream.enabled", havingValue = "true")
public class RealtimeController {
    private final QuoteStore store;
    private final StreamProperties properties;
    public RealtimeController(QuoteStore store, StreamProperties properties) {
        this.store = store;
        this.properties = properties;
    }
    @GetMapping("/snapshot")
    public ResponseEntity<?> snapshot(@RequestParam(defaultValue = "1") int interval) {
        if (!QuoteStore.INTERVALS.contains(interval)) return ResponseEntity.badRequest().body(Map.of("error", "지원하지 않는 봉 주기입니다."));
        return ResponseEntity.ok(store.snapshot(interval, properties.getSymbols()));
    }
}
