package com.kfincheck.realtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/realtime")
@ConditionalOnProperty(name = "stream.enabled", havingValue = "true")
public class RealtimeController {
    private final QuoteStore store;
    private final StreamProperties properties;
    private final ObjectProvider<QuoteSnapshotCache> cache;
    public RealtimeController(QuoteStore store, StreamProperties properties, ObjectProvider<QuoteSnapshotCache> cache) {
        this.store = store;
        this.properties = properties;
        this.cache = cache;
    }
    @GetMapping("/snapshot")
    public ResponseEntity<?> snapshot(@RequestParam(defaultValue = "1") int interval) {
        if (!QuoteStore.INTERVALS.contains(interval)) return ResponseEntity.badRequest().body(Map.of("error", "지원하지 않는 봉 주기입니다."));
        var snapshot = store.snapshot(interval, properties.getSymbols());
        var reader = cache.getIfAvailable();
        if (reader != null && DurableCandle.INTERVALS.contains(interval)) {
            var data = reader.read(interval);
            snapshot.put("quotes", data.quotes());
            snapshot.put("candles", data.candles());
        }
        return ResponseEntity.ok(snapshot);
    }
}
