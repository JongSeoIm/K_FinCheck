package com.example.stock_api.chart;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.example.stock_api.kis.KisService;
import com.example.stock_api.quote.QuoteStore;
import com.example.stock_api.quote.SupportedStocks;

/**
 * 차트 봉 데이터.
 * - 1·5·30초: 서버가 수집한 시세 샘플(QuoteStore)을 집계합니다. 서버 시작 후 수신분만 있고, 거래가 없던 구간에는 봉이 없습니다.
 * - 1~60분: KIS 당일 1분봉을 N분으로 묶습니다. 마지막 봉은 서버의 최신 시세로 맞춥니다.
 * - 일·주·월·년: KIS 기간별 시세(D/W/M/Y)를 씁니다.
 */
@Service
public class ChartService {

    public static class ChartException extends RuntimeException {
        private final int status;

        public ChartException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Set<Integer> SECONDS = Set.of(1, 5, 30);
    private static final Set<Integer> MINUTES = Set.of(1, 3, 5, 10, 15, 30, 60);
    private static final int MAX_SECOND_BARS = 600;

    private record Cached(long at, List<Map<String, Object>> bars) {}

    private final KisService kis;
    private final QuoteStore store;
    private final Map<String, Cached> periodCache = new ConcurrentHashMap<>();

    public ChartService(KisService kis, QuoteStore store) {
        this.kis = kis;
        this.store = store;
    }

    public Map<String, Object> bars(String code, String interval) {
        if (!SupportedStocks.contains(code)) {
            throw new ChartException(400, "지원하지 않는 종목입니다.");
        }
        String source;
        String note;
        List<Map<String, Object>> bars;

        if (interval.matches("\\d+s") && SECONDS.contains(Integer.parseInt(interval.replace("s", "")))) {
            int n = Integer.parseInt(interval.replace("s", ""));
            bars = secondBars(store.samples(code), n);
            source = "서버 수집";
            note = "서버가 시세 수집을 시작한 뒤 받은 값만 표시합니다. 서버를 다시 시작하면 초기화되고, 거래가 없던 구간에는 봉을 만들지 않습니다.";
        } else if (interval.matches("\\d+m") && MINUTES.contains(Integer.parseInt(interval.replace("m", "")))) {
            int n = Integer.parseInt(interval.replace("m", ""));
            bars = minuteBars(kis.minuteCandles(code), n);
            applyLiveToMinute(code, bars, n);
            source = "KIS 당일 분봉";
            note = "오늘 장 시작 이후 분봉이며, 마지막 봉은 서버 최신 시세로 맞춥니다.";
        } else if (Set.of("D", "W", "M", "Y").contains(interval)) {
            bars = periodBars(code, interval);
            applyLiveToPeriod(code, bars, interval);
            source = "KIS 기간별 시세";
            note = switch (interval) {
                case "D" -> "일봉 (최근 약 1년)";
                case "W" -> "주봉 (최근 약 2년)";
                case "M" -> "월봉 (최근 약 8년)";
                default -> "년봉";
            };
        } else {
            throw new ChartException(400, "지원하지 않는 주기입니다.");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("interval", interval);
        body.put("source", source);
        body.put("note", note);
        body.put("bars", bars);
        return body;
    }

    // ───────────── 초봉 ─────────────

    /** 샘플을 N초 단위로 묶습니다. 샘플이 있는 구간만 봉이 됩니다. */
    static List<Map<String, Object>> secondBars(List<QuoteStore.Sample> samples, int n) {
        long size = n * 1000L;
        TreeMap<Long, long[]> buckets = new TreeMap<>(); // key → [open, high, low, close]
        for (QuoteStore.Sample s : samples) {
            long key = s.at() / size;
            long[] bar = buckets.get(key);
            if (bar == null) {
                buckets.put(key, new long[] {s.price(), s.price(), s.price(), s.price()});
            } else {
                bar[1] = Math.max(bar[1], s.price());
                bar[2] = Math.min(bar[2], s.price());
                bar[3] = s.price();
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Long, long[]> e : buckets.entrySet()) {
            long startMs = e.getKey() * size;
            LocalDateTime at = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(startMs), KST);
            long[] bar = e.getValue();
            result.add(bar(startMs / 1000, at.toLocalDate().toString(),
                    at.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss")), bar[0], bar[1], bar[2], bar[3], null));
        }
        int from = Math.max(0, result.size() - MAX_SECOND_BARS);
        return new ArrayList<>(result.subList(from, result.size()));
    }

    // ───────────── 분봉 ─────────────

    /** 1분봉(date, time, open, high, low, close, volume)을 N분 봉으로 묶습니다. */
    static List<Map<String, Object>> minuteBars(List<Map<String, Object>> minutes, int n) {
        TreeMap<Long, long[]> buckets = new TreeMap<>(); // key(epoch초) → [open, high, low, close, volume]
        Map<Long, String[]> labels = new ConcurrentHashMap<>();
        for (Map<String, Object> m : minutes) {
            String date = String.valueOf(m.get("date"));
            String time = String.valueOf(m.get("time"));
            if (!time.matches("\\d{2}:\\d{2}")) {
                continue;
            }
            int minuteOfDay = Integer.parseInt(time.substring(0, 2)) * 60 + Integer.parseInt(time.substring(3, 5));
            int bucketStart = minuteOfDay / n * n;
            LocalDateTime start = LocalDate.parse(date).atTime(bucketStart / 60, bucketStart % 60);
            long key = start.atZone(KST).toEpochSecond();
            long open = num(m.get("open")), high = num(m.get("high")), low = num(m.get("low")), close = num(m.get("close"));
            long volume = num(m.get("volume"));
            long[] bar = buckets.get(key);
            if (bar == null) {
                buckets.put(key, new long[] {open, high, low, close, volume});
                labels.put(key, new String[] {date, String.format("%02d:%02d", bucketStart / 60, bucketStart % 60)});
            } else {
                bar[1] = Math.max(bar[1], high);
                bar[2] = Math.min(bar[2], low);
                bar[3] = close;
                bar[4] += volume;
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Long, long[]> e : buckets.entrySet()) {
            long[] b = e.getValue();
            String[] label = labels.get(e.getKey());
            result.add(bar(e.getKey(), label[0], label[1], b[0], b[1], b[2], b[3], b[4]));
        }
        return result;
    }

    /** 마지막 분봉이 지금 시각의 봉이면 서버 최신 시세로 종가·고가·저가를 맞춥니다. 새 봉은 만들지 않습니다. */
    private void applyLiveToMinute(String code, List<Map<String, Object>> bars, int n) {
        QuoteStore.Quote quote = store.get(code);
        if (quote == null || bars.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(KST);
        int minuteOfDay = now.getHour() * 60 + now.getMinute();
        int bucketStart = minuteOfDay / n * n;
        long key = now.toLocalDate().atTime(bucketStart / 60, bucketStart % 60).atZone(KST).toEpochSecond();
        Map<String, Object> last = bars.get(bars.size() - 1);
        if (((Number) last.get("t")).longValue() == key) {
            mergeLive(last, quote.price());
        }
    }

    // ───────────── 일·주·월·년 ─────────────

    private List<Map<String, Object>> periodBars(String code, String interval) {
        String key = code + ":" + interval;
        Cached hit = periodCache.get(key);
        if (hit != null && System.currentTimeMillis() - hit.at() < 600_000L) {
            return deepCopy(hit.bars());
        }
        List<Map<String, Object>> bars;
        if ("D".equals(interval)) {
            bars = new ArrayList<>();
            for (Map<String, Object> row : kis.dailyCandles(code, "1Y")) {
                bars.add(bar(0, String.valueOf(row.get("date")), null, num(row.get("open")), num(row.get("high")),
                        num(row.get("low")), num(row.get("close")), num(row.get("volume"))));
            }
        } else {
            LocalDate today = LocalDate.now(KST);
            LocalDate from = switch (interval) {
                case "W" -> today.minusWeeks(99);
                case "M" -> today.minusMonths(99);
                default -> today.minusYears(30);
            };
            Map<String, Object> res = kis.get(
                    "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice", "FHKST03010100",
                    Map.of("FID_COND_MRKT_DIV_CODE", "J", "FID_INPUT_ISCD", code,
                            "FID_INPUT_DATE_1", from.format(YMD), "FID_INPUT_DATE_2", today.format(YMD),
                            "FID_PERIOD_DIV_CODE", interval, "FID_ORG_ADJ_PRC", "0"));
            TreeMap<String, Map<String, Object>> byDate = new TreeMap<>();
            if (res.get("output2") instanceof List<?> rows) {
                for (Object o : rows) {
                    if (!(o instanceof Map<?, ?> m)) {
                        continue;
                    }
                    String d = String.valueOf(m.get("stck_bsop_date")).trim();
                    if (d.length() != 8) {
                        continue;
                    }
                    String date = d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6, 8);
                    long close = num(m.get("stck_clpr"));
                    if (close <= 0) {
                        continue;
                    }
                    byDate.put(date, bar(0, date, null, num(m.get("stck_oprc")), num(m.get("stck_hgpr")),
                            num(m.get("stck_lwpr")), close, num(m.get("acml_vol"))));
                }
            }
            bars = new ArrayList<>(byDate.values());
        }
        periodCache.put(key, new Cached(System.currentTimeMillis(), deepCopy(bars)));
        return bars;
    }

    /** 오늘이 속한 마지막 봉이면 서버 최신 시세로 종가·고가·저가를 맞춥니다. */
    private void applyLiveToPeriod(String code, List<Map<String, Object>> bars, String interval) {
        QuoteStore.Quote quote = store.get(code);
        if (quote == null || bars.isEmpty()) {
            return;
        }
        Map<String, Object> last = bars.get(bars.size() - 1);
        LocalDate lastDate = LocalDate.parse(String.valueOf(last.get("date")));
        LocalDate today = LocalDate.now(KST);
        boolean samePeriod = switch (interval) {
            case "D" -> lastDate.equals(today);
            case "W" -> lastDate.get(IsoFields.WEEK_BASED_YEAR) == today.get(IsoFields.WEEK_BASED_YEAR)
                    && lastDate.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) == today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            case "M" -> lastDate.getYear() == today.getYear() && lastDate.getMonth() == today.getMonth();
            default -> lastDate.getYear() == today.getYear();
        };
        boolean tradingDay = today.getDayOfWeek() != DayOfWeek.SATURDAY && today.getDayOfWeek() != DayOfWeek.SUNDAY
                && !LocalTime.now(KST).isBefore(LocalTime.of(9, 0));
        if (samePeriod && tradingDay) {
            mergeLive(last, quote.price());
        }
    }

    // ───────────── 공통 ─────────────

    private static void mergeLive(Map<String, Object> bar, long price) {
        bar.put("close", price);
        bar.put("high", Math.max(num(bar.get("high")), price));
        bar.put("low", Math.min(num(bar.get("low")), price));
    }

    private static Map<String, Object> bar(long t, String date, String time, long open, long high, long low,
                                           long close, Long volume) {
        Map<String, Object> bar = new LinkedHashMap<>();
        bar.put("t", t);
        bar.put("date", date);
        bar.put("time", time);
        bar.put("open", open);
        bar.put("high", high);
        bar.put("low", low);
        bar.put("close", close);
        bar.put("volume", volume);
        return bar;
    }

    private static List<Map<String, Object>> deepCopy(List<Map<String, Object>> bars) {
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> b : bars) {
            copy.add(new LinkedHashMap<>(b));
        }
        return copy;
    }

    private static long num(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value).trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
