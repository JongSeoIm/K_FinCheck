package com.example.stock_api.quote;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.stock_api.kis.KisService;

/**
 * 지원 종목의 KIS 현재가를 서버가 대신 읽어 QuoteStore 에 넣습니다.
 * - KIS 초당 호출 한도를 지키기 위해 1초에 한 번, 종목을 돌아가며 한 개씩만 읽습니다.
 *   (장중 종목당 약 3초마다 갱신, 장 밖에는 종목당 60초마다)
 * - "초당 거래건수 초과" 응답이 오면 5초 동안 쉬었다가 다시 읽습니다.
 * - 화면 수와 상관없이 KIS 호출 횟수는 늘지 않고, 한 종목 실패는 다른 종목에 영향을 주지 않습니다.
 */
@Component
public class QuoteCollector {

    private static final Logger LOG = LoggerFactory.getLogger(QuoteCollector.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalTime OPEN = LocalTime.of(8, 50);
    private static final LocalTime CLOSE = LocalTime.of(15, 40);
    private static final long MARKET_GAP_MS = 2_500L;
    private static final long OFF_HOURS_GAP_MS = 60_000L;
    private static final long RATE_LIMIT_PAUSE_MS = 5_000L;

    private final KisService kis;
    private final QuoteStore store;
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, Long> lastFetch = new ConcurrentHashMap<>();
    private final Map<String, Long> lastWarn = new ConcurrentHashMap<>();
    private volatile long pausedUntil = 0L;
    private int cursor = 0;

    public QuoteCollector(KisService kis, QuoteStore store) {
        this.kis = kis;
        this.store = store;
    }

    static boolean marketHours(LocalDateTime now) {
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime time = now.toLocalTime();
        return !time.isBefore(OPEN) && !time.isAfter(CLOSE);
    }

    @Scheduled(fixedDelay = 1000)
    public void collect() {
        long nowMs = System.currentTimeMillis();
        if (nowMs < pausedUntil) {
            return;
        }
        boolean open = marketHours(LocalDateTime.now(KST));
        long gap = open ? MARKET_GAP_MS : OFF_HOURS_GAP_MS;
        int size = SupportedStocks.CODES.size();
        for (int i = 0; i < size; i++) {
            int index = (cursor + i) % size;
            String code = SupportedStocks.CODES.get(index);
            if (nowMs - lastFetch.getOrDefault(code, 0L) < gap) {
                continue;
            }
            cursor = index + 1; // 다음 틱에는 다음 종목부터
            lastFetch.put(code, nowMs);
            try {
                fetch(code, open);
            } catch (Exception e) {
                String message = String.valueOf(e.getMessage());
                if (message.contains("초당") || message.contains("EGW00201")) {
                    pausedUntil = nowMs + RATE_LIMIT_PAUSE_MS;
                }
                // 키·주소가 섞일 수 있어 예외 종류만 남기되, 호출 한도 초과 안내만 문구를 함께 남깁니다.
                if (nowMs - lastWarn.getOrDefault(code, 0L) > 60_000L) {
                    lastWarn.put(code, nowMs);
                    boolean limited = message.contains("초당") || message.contains("EGW00201");
                    LOG.warn("시세 수집 실패 {}: {}{}", code, e.getClass().getSimpleName(),
                            limited ? " (KIS 초당 호출 한도 초과, 잠시 쉼)" : "");
                }
            }
            return; // 한 번에 한 종목만
        }
    }

    private void fetch(String code, boolean marketOpen) {
        Map<String, Object> response = kis.get(
                "/uapi/domestic-stock/v1/quotations/inquire-price", "FHKST01010100",
                Map.of("FID_COND_MRKT_DIV_CODE", "J", "FID_INPUT_ISCD", code));
        if (!(response.get("output") instanceof Map<?, ?> out)) {
            throw new IllegalStateException("output 없음");
        }
        long price = toLong(out.get("stck_prpr"));
        if (price <= 0) {
            throw new IllegalStateException("가격 없음");
        }
        String sign = String.valueOf(out.get("prdy_vrss_sign"));
        boolean down = "4".equals(sign) || "5".equals(sign);
        long diff = Math.abs(toLong(out.get("prdy_vrss")));
        double rate = Math.abs(toDouble(out.get("prdy_ctrt")));
        long volume = toLong(out.get("acml_vol"));

        long now = System.currentTimeMillis();
        QuoteStore.Quote quote = new QuoteStore.Quote(code, price, down ? -diff : diff, down ? -rate : rate,
                toLong(out.get("stck_oprc")), toLong(out.get("stck_hgpr")), toLong(out.get("stck_lwpr")),
                toLong(out.get("stck_sdpr")), volume, Instant.ofEpochMilli(now).toString(),
                sequence.incrementAndGet());
        // 장 밖에서는 최신 시세만 갱신하고, 초·분봉용 샘플은 쌓지 않습니다.
        store.put(quote, marketOpen ? new QuoteStore.Sample(now, price, volume) : null);
    }

    private static long toLong(Object value) {
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value).trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static double toDouble(Object value) {
        try {
            return value == null ? 0.0 : Double.parseDouble(String.valueOf(value).trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
