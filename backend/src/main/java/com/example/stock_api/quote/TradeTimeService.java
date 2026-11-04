package com.example.stock_api.quote;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.example.stock_api.kis.KisService;

/**
 * 마지막 체결 시각 (KIS '주식현재가 당일시간대별체결', tr_id FHPST01060000).
 * - 현재 시각까지의 체결 목록에서 가장 늦은 체결 시각을 고릅니다.
 * - KIS 응답에는 시각(HHMMSS)만 있고 날짜가 없어서, 마지막 거래일 날짜는 요일·시간으로 추정합니다.
 *   (공휴일은 반영하지 않으므로 공휴일 다음 날에는 날짜가 하루 이상 틀릴 수 있습니다.)
 */
@Service
public class TradeTimeService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HHmmss");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 0);

    private record Cached(long at, Map<String, Object> data) {}

    private final KisService kis;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public TradeTimeService(KisService kis) {
        this.kis = kis;
    }

    /** 가장 최근 장 날짜 추정: 평일 09시 이후면 오늘, 그 외에는 직전 평일. */
    static LocalDate lastSessionDate(LocalDateTime now) {
        LocalDate date = now.toLocalDate();
        boolean weekday = date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY;
        if (!(weekday && !now.toLocalTime().isBefore(MARKET_OPEN))) {
            date = date.minusDays(1);
        }
        while (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            date = date.minusDays(1);
        }
        return date;
    }

    public Map<String, Object> latest(String code) {
        Cached hit = cache.get(code);
        if (hit != null && System.currentTimeMillis() - hit.at() < 5000) {
            return hit.data();
        }
        LocalDateTime now = LocalDateTime.now(KST);
        Map<String, Object> response = kis.get(
                "/uapi/domestic-stock/v1/quotations/inquire-time-itemconclusion", "FHPST01060000",
                Map.of("FID_COND_MRKT_DIV_CODE", "J", "FID_INPUT_ISCD", code, "FID_INPUT_HOUR_1", now.format(HMS)));

        String latest = null;
        if (response.get("output2") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> row) {
                    String time = String.valueOf(row.get("stck_cntg_hour")).trim();
                    if (time.matches("\\d{6}") && (latest == null || time.compareTo(latest) > 0)) {
                        latest = time;
                    }
                }
            }
        }
        if (latest == null) {
            throw new IllegalStateException("체결 내역 없음");
        }

        LocalDate date = lastSessionDate(now);
        LocalTime time = LocalTime.parse(latest, HMS);
        OffsetDateTime at = LocalDateTime.of(date, time).atZone(KST).toOffsetDateTime();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tradedAt", at.toString());
        result.put("tradeDate", date.toString());
        result.put("tradeTime", time.format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        result.put("exchange", "KRX");
        cache.put(code, new Cached(System.currentTimeMillis(), result));
        return result;
    }
}
