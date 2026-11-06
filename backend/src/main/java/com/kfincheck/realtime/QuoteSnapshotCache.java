package com.kfincheck.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

@Service
@ConditionalOnProperty(name = {"stream.enabled", "stream.persistence.enabled", "stream.cache.enabled"}, havingValue = "true")
public class QuoteSnapshotCache {
    private static final Duration TTL = Duration.ofSeconds(1);
    private static final long RETRY_DELAY_NANOS = Duration.ofSeconds(3).toNanos();
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final QuotePersistenceRepository repository;
    private final List<String> symbols;
    private final String source;
    private final String keyPrefix;
    private volatile Long retryAfter;

    public record Data(Map<String, QuoteEvent> quotes, Map<String, List<QuoteStore.ObservedCandle>> candles) {}

    public QuoteSnapshotCache(StringRedisTemplate redis, ObjectMapper mapper, QuotePersistenceRepository repository,
                              StreamProperties properties,
                              @Value("${stream.persistence.source:KIS_WEBSOCKET_KRX}") String source,
                              @Value("${stream.cache.namespace:kfincheck-local}") String namespace) {
        this.redis = redis;
        this.mapper = mapper;
        this.repository = repository;
        this.symbols = List.copyOf(properties.getSymbols());
        this.source = source;
        this.keyPrefix = namespace + ":quotes:v1:" + source + ":" + String.join(",", symbols) + ":";
    }

    public Data read(int interval) {
        String key = keyPrefix + interval;
        Long retry = retryAfter;
        boolean useRedis = retry == null || System.nanoTime() - retry >= 0;
        if (useRedis) {
            try {
                String payload = redis.opsForValue().get(key);
                if (payload != null) {
                    Data cached = mapper.readValue(payload, Data.class);
                    if (cached != null && cached.quotes() != null && cached.candles() != null) return cached;
                }
            } catch (DataAccessException e) {
                retryAfter = System.nanoTime() + RETRY_DELAY_NANOS;
                useRedis = false;
            } catch (JsonProcessingException ignored) {
                // A malformed cache entry can be replaced from the database.
            }
        }
        Data data = load(interval);
        if (useRedis) {
            try {
                redis.opsForValue().set(key, mapper.writeValueAsString(data), TTL);
            } catch (DataAccessException e) {
                retryAfter = System.nanoTime() + RETRY_DELAY_NANOS;
            } catch (JsonProcessingException ignored) {
                // Cache serialization must not prevent the database response.
            }
        }
        return data;
    }

    private Data load(int interval) {
        Map<String, QuoteEvent> quotes = new LinkedHashMap<>();
        Map<String, List<QuoteStore.ObservedCandle>> candles = new LinkedHashMap<>();
        for (var projection : repository.load(source, symbols, List.of(interval))) {
            if (projection.quote() != null) quotes.put(projection.quote().stockCode(), projection.quote());
            for (var candle : projection.candles()) {
                candles.computeIfAbsent(candle.stockCode(), symbol -> new ArrayList<>()).add(candle.observed());
            }
        }
        // The repository returns the newest candles first. The chart expects ascending time.
        candles.values().forEach(Collections::reverse);
        return new Data(quotes, candles);
    }
}
