package com.example.stock_api.opinion;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.example.stock_api.kis.KisService;

/**
 * 종목별 증권사 투자의견 (KIS 국내주식 종목투자의견).
 * - monthly : 최근 약 4개월 리포트를 월별 매수/중립/매도 건수로 집계
 * - target  : 목표가 평균/최저/최고와 현재가 대비 상승 여력
 * - reports : 최근 리포트 3건 (증권사, 날짜, 목표가, 의견)
 * 리포트 원본은 1시간 캐시하고, 현재가는 매번(KisService 의 2초 캐시) 새로 읽어 상승 여력을 계산합니다.
 */
@Service
public class OpinionService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final long CACHE_MS = 3_600_000L;

    private final KisService kis;

    private record Report(String date, String firm, String opinion, long target) {}

    private record Cached(long at, List<Report> reports) {}

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public OpinionService(KisService kis) {
        this.kis = kis;
    }

    /** KIS 원본 응답(점검용). */
    public Map<String, Object> raw(String code) {
        LocalDate end = LocalDate.now(KST);
        return kis.get("/uapi/domestic-stock/v1/quotations/invest-opinion", "FHKST663300C0", Map.of(
                "FID_COND_MRKT_DIV_CODE", "J",
                "FID_COND_SCR_DIV_CODE", "16633",
                "FID_INPUT_ISCD", code,
                "FID_INPUT_DATE_1", end.minusDays(120).format(YMD),
                "FID_INPUT_DATE_2", end.format(YMD)));
    }

    public Map<String, Object> summary(String code) {
        List<Report> reports = reports(code);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("monthly", monthly(reports));
        result.put("target", target(code, reports));
        List<Map<String, Object>> recent = new ArrayList<>();
        for (Report r : reports.stream().limit(3).toList()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("firm", r.firm());
            m.put("date", r.date().substring(4, 6) + "/" + r.date().substring(6, 8));
            m.put("opinion", r.opinion());
            m.put("target", r.target() > 0 ? r.target() : null);
            recent.add(m);
        }
        result.put("reports", recent);
        return result;
    }

    /** 최신 날짜순 리포트 목록 (1시간 캐시). */
    private synchronized List<Report> reports(String code) {
        Cached hit = cache.get(code);
        if (hit != null && System.currentTimeMillis() - hit.at() < CACHE_MS) {
            return hit.reports();
        }

        Object output = raw(code).get("output");
        List<?> rows = output instanceof List<?> list ? list : output instanceof Map<?, ?> ? List.of(output) : List.of();

        List<Report> reports = new ArrayList<>();
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            String date = String.valueOf(row.get("stck_bsop_date")).trim();
            if (date.length() != 8) {
                continue;
            }
            reports.add(new Report(date,
                    String.valueOf(row.get("mbcr_name")).trim(),
                    String.valueOf(row.get("invt_opnn")).trim(),
                    toLong(row.get("hts_goal_prc"))));
        }
        reports.sort(Comparator.comparing(Report::date).reversed());
        cache.put(code, new Cached(System.currentTimeMillis(), reports));
        return reports;
    }

    private static List<Map<String, Object>> monthly(List<Report> reports) {
        // 월 → [강력매수, 매수, 중립, 매도, 강력매도]
        Map<String, int[]> months = new TreeMap<>();
        for (Report r : reports) {
            int bucket = bucket(r.opinion());
            if (bucket < 0) {
                continue;
            }
            String period = r.date().substring(0, 4) + "-" + r.date().substring(4, 6) + "-01";
            months.computeIfAbsent(period, k -> new int[5])[bucket]++;
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, int[]> e : months.entrySet()) {
            int[] c = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("period", e.getKey());
            m.put("strongBuy", c[0]);
            m.put("buy", c[1]);
            m.put("hold", c[2]);
            m.put("sell", c[3]);
            m.put("strongSell", c[4]);
            result.add(m);
        }
        return result;
    }

    /** 목표가가 하나도 없으면 null. 현재가를 못 읽으면 상승 여력만 null. */
    private Map<String, Object> target(String code, List<Report> reports) {
        List<Long> targets = reports.stream().map(Report::target).filter(t -> t > 0).toList();
        if (targets.isEmpty()) {
            return null;
        }
        long sum = 0;
        long min = Long.MAX_VALUE;
        long max = 0;
        for (long t : targets) {
            sum += t;
            min = Math.min(min, t);
            max = Math.max(max, t);
        }
        long average = Math.round((double) sum / targets.size());

        Long price = null;
        Double upside = null;
        try {
            Object p = kis.currentPrice(code).get("price");
            if (p instanceof Number n && n.longValue() > 0) {
                price = n.longValue();
                upside = (average - price) * 100.0 / price;
            }
        } catch (RuntimeException ignored) {
            // 현재가를 못 읽어도 목표가 자체는 보여 줍니다.
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("average", average);
        m.put("min", min);
        m.put("max", max);
        m.put("count", targets.size());
        m.put("currentPrice", price);
        m.put("upsidePercent", upside);
        return m;
    }

    private static long toLong(Object value) {
        try {
            return Long.parseLong(String.valueOf(value).trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 의견 문구 → 0 강력매수, 1 매수, 2 중립, 3 매도, 4 강력매도, -1 분류 불가(예: NR) */
    static int bucket(String opinion) {
        String s = opinion == null ? "" : opinion.toLowerCase().replace(" ", "");
        if (s.contains("적극매도") || s.contains("strongsell")) {
            return 4;
        }
        if (s.contains("적극매수") || s.contains("strongbuy") || s.contains("강력매수")) {
            return 0;
        }
        if (s.contains("매도") || s.contains("sell") || s.contains("비중축소") || s.contains("underperform")
                || s.contains("reduce") || s.contains("underweight")) {
            return 3;
        }
        if (s.contains("중립") || s.contains("hold") || s.contains("neutral") || s.contains("marketperform")
                || s.contains("비중유지") || s.contains("보유")) {
            return 2;
        }
        if (s.contains("매수") || s.contains("buy") || s.contains("outperform") || s.contains("비중확대")
                || s.contains("overweight") || s.contains("trading")) {
            return 1;
        }
        return -1;
    }
}
