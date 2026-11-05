package com.example.stock_api.realtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.stock_api.common.MarketHours;
import com.example.stock_api.kis.KisService;
import com.example.stock_api.stock.StockService;

/**
 * 실시간 체결이 30초 넘게 없는 종목은 REST 현재가로 채워 WebSocket 으로 내보냅니다.
 * (KIS 실시간 연결이 끊겼을 때, 장 마감 후 등) 장중 10초, 장 마감 후 60초 간격.
 */
@Component
public class RestQuoteRefresher {

    private static final Logger log = LoggerFactory.getLogger(RestQuoteRefresher.class);
    private static final long STALE_MS = 30_000;

    private final KisService kis;
    private final StockService stocks;
    private final QuoteStore hub;

    public RestQuoteRefresher(KisService kis, StockService stocks, QuoteStore hub) {
        this.kis = kis;
        this.stocks = stocks;
        this.hub = hub;
    }

    private final Map<String, Long> lastRestAt = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 5_000, initialDelay = 3_000)
    public void refresh() {
        if (!kis.isConfigured()) return;
        long now = System.currentTimeMillis();
        long interval = MarketHours.isOpen() ? 10_000 : 60_000;

        for (String code : stocks.codes()) {
            long sinceRest = now - lastRestAt.getOrDefault(code, 0L);
            boolean freshTick = hub.isRealtimeConnected() && now - hub.lastRealtimeAt(code) < STALE_MS;
            // 실시간 체결이 잘 들어와도 시가총액·상장주수는 REST 로만 오므로, 없으면 30초마다 · 있으면 5분마다 다시 받음
            boolean needSnapshot = hub.missingMarketCap(code) ? sinceRest >= 30_000 : sinceRest >= 300_000;
            if (freshTick && !needSnapshot) continue;
            if (!freshTick && sinceRest < interval && !needSnapshot) continue;
            lastRestAt.put(code, now);
            try {
                hub.publishRest(kis.currentPrice(code));
            } catch (RuntimeException e) {
                log.warn("REST 현재가 실패 ({}): {}", code, e.getMessage());
            }
        }
    }
}