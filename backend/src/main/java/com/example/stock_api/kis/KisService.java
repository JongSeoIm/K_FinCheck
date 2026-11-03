package com.example.stock_api.kis;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import com.example.stock_api.common.ApiException;

@Service
public class KisService {

    private static final Path TOKEN_FILE = Path.of(".kis-token");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HHmmss");

    private final RestClient rest;
    private final String appKey;
    private final String appSecret;

    private String token;
    private Instant expiresAt = Instant.EPOCH;

    // 같은 종목은 2초 동안 캐시 (KIS 호출 횟수 제한 방지)
    private record Cached(long at, Map<String, Object> data) {}
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    // 일봉 캐시 (10분)
    private record CachedList(long at, List<Map<String, Object>> data) {}
    private final Map<String, CachedList> chartCache = new ConcurrentHashMap<>();

    public KisService(@Value("${kis.base-url}") String baseUrl,
                      @Value("${kis.app-key}") String appKey,
                      @Value("${kis.app-secret}") String appSecret) {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(8000);
        this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.appKey = appKey;
        this.appSecret = appSecret;
    }

    public boolean isConfigured() {
        return appKey != null && !appKey.isBlank() && appSecret != null && !appSecret.isBlank();
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "KIS_NOT_CONFIGURED",
                    "KIS 앱키가 설정되지 않았습니다. application-local.properties 를 확인하세요.");
        }
    }

    /** 실시간 웹소켓 접속키 발급 (/oauth2/Approval). 웹소켓을 새로 연결할 때마다 받습니다. */
    public String approvalKey() {
        requireConfigured();
        Map<String, Object> res = rest.post()
                .uri("/oauth2/Approval")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "grant_type", "client_credentials",
                        "appkey", appKey,
                        "secretkey", appSecret))
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        if (res == null || res.get("approval_key") == null) {
            throw new IllegalStateException("KIS 웹소켓 접속키 발급 실패: " + res);
        }
        return String.valueOf(res.get("approval_key"));
    }

    // 토큰: 메모리 -> 파일 -> 신규 발급 (KIS는 1분에 1회만 발급 가능)
    private synchronized String token() {
        requireConfigured();
        if (token != null && Instant.now().isBefore(expiresAt)) {
            return token;
        }

        try {
            if (Files.exists(TOKEN_FILE)) {
                List<String> lines = Files.readAllLines(TOKEN_FILE, StandardCharsets.UTF_8);
                if (lines.size() >= 2) {
                    Instant exp = Instant.ofEpochSecond(Long.parseLong(lines.get(1).trim()));
                    if (Instant.now().isBefore(exp)) {
                        token = lines.get(0).trim();
                        expiresAt = exp;
                        return token;
                    }
                }
            }
        } catch (Exception ignored) {
            // 파일이 깨졌으면 새로 발급
        }

        Map<String, Object> res = rest.post()
                .uri("/oauth2/tokenP")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "grant_type", "client_credentials",
                        "appkey", appKey,
                        "appsecret", appSecret))
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});

        if (res == null || res.get("access_token") == null) {
            throw new IllegalStateException("KIS 토큰 발급 실패: " + res);
        }

        token = (String) res.get("access_token");
        long seconds = 86400;
        if (res.get("expires_in") instanceof Number n) {
            seconds = n.longValue();
        }
        expiresAt = Instant.now().plusSeconds(Math.max(seconds - 600, 60));

        try {
            Files.writeString(TOKEN_FILE, token + "\n" + expiresAt.getEpochSecond(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 저장 실패해도 메모리 캐시로 동작
        }
        return token;
    }

    // KIS REST 는 초당 호출 수 제한이 있어 호출 사이를 최소 70ms 띄웁니다 (초당 약 14건).
    private static final long MIN_GAP_MS = 70;
    private long lastCallAt = 0;

    private void throttle() {
        synchronized (this) {
            long wait = lastCallAt + MIN_GAP_MS - System.currentTimeMillis();
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastCallAt = System.currentTimeMillis();
        }
    }

    private static long toLong(Object v) {
        if (v == null) return 0L;
        try {
            return Long.parseLong(v.toString().trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static double toDouble(Object v) {
        if (v == null) return 0.0;
        try {
            return Double.parseDouble(v.toString().trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    // 현재가 조회
    public Map<String, Object> currentPrice(String stockCode) {
        requireConfigured();
        Cached hit = cache.get(stockCode);
        if (hit != null && System.currentTimeMillis() - hit.at() < 2000) {
            return hit.data();
        }

        String bearer = "Bearer " + token();
        throttle();
        Map<String, Object> response = rest.get()
                .uri(u -> u.path("/uapi/domestic-stock/v1/quotations/inquire-price")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                        .queryParam("FID_INPUT_ISCD", stockCode)
                        .build())
                .header("authorization", bearer)
                .header("appkey", appKey)
                .header("appsecret", appSecret)
                .header("tr_id", "FHKST01010100")
                .header("custtype", "P")
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});

        if (response == null || !"0".equals(String.valueOf(response.get("rt_cd")))
                || !(response.get("output") instanceof Map<?, ?> output)) {
            String msg = response == null ? "응답 없음" : String.valueOf(response.get("msg1"));
            throw new IllegalStateException("KIS 조회 실패: " + msg);
        }

        long price = toLong(output.get("stck_prpr"));
        long shares = toLong(output.get("lstn_stcn"));
        String sign = String.valueOf(output.get("prdy_vrss_sign"));
        boolean down = "4".equals(sign) || "5".equals(sign);

        long diff = Math.abs(toLong(output.get("prdy_vrss")));
        double rate = Math.abs(toDouble(output.get("prdy_ctrt")));

        Map<String, Object> result = new HashMap<>();
        result.put("code", stockCode);
        result.put("source", "rest");
        result.put("price", price);
        result.put("priceChange", down ? -diff : diff);
        result.put("change", down ? -rate : rate);
        result.put("open", toLong(output.get("stck_oprc")));
        result.put("high", toLong(output.get("stck_hgpr")));
        result.put("low", toLong(output.get("stck_lwpr")));
        result.put("prevClose", toLong(output.get("stck_sdpr")));
        // 시가총액 = 현재가 × 상장주수. 상장주수가 없으면 HTS 시가총액(억원) 사용
        long marketCap = shares > 0 ? price * shares : toLong(output.get("hts_avls")) * 100_000_000L;
        result.put("listedShares", shares > 0 ? shares : null);
        result.put("marketCapitalization", marketCap > 0 ? marketCap : null);
        result.put("updatedAt", Instant.now().toString());

        cache.put(stockCode, new Cached(System.currentTimeMillis(), result));
        return result;
    }

    // ───────────── 차트용 ─────────────

    private static String str(Object v) {
        return v == null ? "" : v.toString().trim();
    }

    private static void pause() {
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // KIS GET 공통 호출 (rt_cd 확인 포함)
    private Map<String, Object> kisGet(String path, String trId, Map<String, String> params) {
        String bearer = "Bearer " + token();
        throttle();
        Map<String, Object> response = rest.get()
                .uri(u -> {
                    UriBuilder b = u.path(path);
                    for (Map.Entry<String, String> e : params.entrySet()) {
                        b = b.queryParam(e.getKey(), e.getValue());
                    }
                    return b.build();
                })
                .header("authorization", bearer)
                .header("appkey", appKey)
                .header("appsecret", appSecret)
                .header("tr_id", trId)
                .header("custtype", "P")
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});

        if (response == null || !"0".equals(String.valueOf(response.get("rt_cd")))) {
            String msg = response == null ? "응답 없음" : String.valueOf(response.get("msg1"));
            throw new IllegalStateException("KIS 차트 조회 실패: " + msg);
        }
        return response;
    }

    // 일봉 한 번 조회 (최대 100개). 날짜 오름차순으로 돌려줌
    private List<Map<String, Object>> fetchDaily(String code, LocalDate from, LocalDate to) {
        Map<String, Object> res = kisGet(
                "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice",
                "FHKST03010100",
                Map.of(
                        "FID_COND_MRKT_DIV_CODE", "J",
                        "FID_INPUT_ISCD", code,
                        "FID_INPUT_DATE_1", from.format(YMD),
                        "FID_INPUT_DATE_2", to.format(YMD),
                        "FID_PERIOD_DIV_CODE", "D",
                        "FID_ORG_ADJ_PRC", "0"));

        List<Map<String, Object>> out = new ArrayList<>();
        if (res.get("output2") instanceof List<?> rows) {
            for (Object o : rows) {
                if (!(o instanceof Map<?, ?> m)) continue;
                String d = str(m.get("stck_bsop_date"));
                if (d.length() != 8) continue;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("date", d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6, 8));
                c.put("open", toLong(m.get("stck_oprc")));
                c.put("high", toLong(m.get("stck_hgpr")));
                c.put("low", toLong(m.get("stck_lwpr")));
                c.put("close", toLong(m.get("stck_clpr")));
                c.put("volume", toLong(m.get("acml_vol")));
                out.add(c);
            }
        }
        out.sort((a, b) -> ((String) a.get("date")).compareTo((String) b.get("date")));
        return out;
    }

    // 일봉: range = 1W / 1M / 1Y
    public List<Map<String, Object>> dailyCandles(String code, String range) {
        String key = code + ":" + range;
        CachedList hit = chartCache.get(key);
        if (hit != null && System.currentTimeMillis() - hit.at() < 600_000) {
            return hit.data();
        }

        int days = switch (range) {
            case "1W" -> 8;
            case "1M" -> 31;
            default -> 365;
        };
        LocalDate today = LocalDate.now(KST);
        LocalDate limit = today.minusDays(days);

        Map<String, Map<String, Object>> byDate = new TreeMap<>();
        LocalDate end = today;
        for (int i = 0; i < 5 && end.isAfter(limit); i++) {
            LocalDate start = end.minusDays(130);
            if (start.isBefore(limit)) start = limit;

            List<Map<String, Object>> rows = fetchDaily(code, start, end);
            if (rows.isEmpty()) break;
            for (Map<String, Object> c : rows) {
                byDate.put((String) c.get("date"), c);
            }
            end = start.minusDays(1);
            pause();
        }

        List<Map<String, Object>> result = new ArrayList<>(byDate.values());
        chartCache.put(key, new CachedList(System.currentTimeMillis(), result));
        return result;
    }

    // 당일 1분봉: 처음엔 하루치를 모으고, 그 뒤에는 최근 2페이지(약 60분)만 새로 받아 합칩니다.
    private final Map<String, TreeMap<String, Map<String, Object>>> minuteStore = new ConcurrentHashMap<>();
    private final Map<String, Long> minuteValidUntil = new ConcurrentHashMap<>();

    public List<Map<String, Object>> minuteCandles(String code) {
        TreeMap<String, Map<String, Object>> store =
                minuteStore.computeIfAbsent(code, k -> new TreeMap<>());

        synchronized (store) {
            Long until = minuteValidUntil.get(code);
            if (until != null && System.currentTimeMillis() < until && !store.isEmpty()) {
                return new ArrayList<>(store.values());
            }

            String nowHms = LocalTime.now(KST).format(HMS);
            String today = LocalDate.now(KST).format(YMD);
            String todayIso = LocalDate.now(KST).toString();

            // 날짜가 바뀌었으면 처음부터 다시 모음
            boolean sameDay = !store.isEmpty()
                    && todayIso.equals(store.firstEntry().getValue().get("date"));
            if (!sameDay) store.clear();

            boolean full = store.isEmpty();
            int maxPages = full ? 16 : 2;
            String hour = nowHms.compareTo("153000") > 0 ? "153000" : nowHms;
            String day = null;

            try {
                for (int i = 0; i < maxPages; i++) {
                    Map<String, Object> res = kisGet(
                            "/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice",
                            "FHKST03010200",
                            Map.of(
                                    "FID_COND_MRKT_DIV_CODE", "J",
                                    "FID_INPUT_ISCD", code,
                                    "FID_INPUT_HOUR_1", hour,
                                    "FID_PW_DATA_INCU_YN", "Y",
                                    "FID_ETC_CLS_CODE", ""));

                    if (!(res.get("output2") instanceof List<?> rows) || rows.isEmpty()) break;

                    String oldest = null;
                    for (Object o : rows) {
                        if (!(o instanceof Map<?, ?> m)) continue;
                        String d = str(m.get("stck_bsop_date"));
                        String t = str(m.get("stck_cntg_hour"));
                        if (d.isEmpty() || t.length() != 6) continue;
                        if (day == null) day = d;
                        if (!day.equals(d)) continue;
                        if (d.equals(today) && t.compareTo(nowHms) > 0) continue; // 아직 오지 않은 시간 제외

                        Map<String, Object> c = new LinkedHashMap<>();
                        c.put("date", d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6, 8));
                        c.put("time", t.substring(0, 2) + ":" + t.substring(2, 4));
                        c.put("open", toLong(m.get("stck_oprc")));
                        c.put("high", toLong(m.get("stck_hgpr")));
                        c.put("low", toLong(m.get("stck_lwpr")));
                        c.put("close", toLong(m.get("stck_prpr")));
                        c.put("volume", toLong(m.get("cntg_vol")));
                        store.put(t, c);

                        if (oldest == null || t.compareTo(oldest) < 0) oldest = t;
                    }

                    // 장 시작(09:00대)까지 왔거나 더 가져올 게 없으면 끝
                    if (oldest == null || oldest.compareTo("090100") <= 0) break;
                    hour = LocalTime.parse(oldest, HMS).minusMinutes(1).format(HMS);
                    pause();
                }
            } catch (RuntimeException e) {
                if (full) store.clear(); // 하루치 모으다 실패하면 처음부터 다시
                throw e;
            }

            // 오늘 데이터면 5초, 휴장일/장 시작 전이면 10분 동안 재사용
            boolean live = !store.isEmpty()
                    && todayIso.equals(store.lastEntry().getValue().get("date"));
            minuteValidUntil.put(code, System.currentTimeMillis() + (live ? 5_000L : 600_000L));
            return new ArrayList<>(store.values());
        }
    }

    // ───────────── 뉴스 · 투자의견 ─────────────

    private record CachedAny(long at, Object data) {}
    private final Map<String, CachedAny> extraCache = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, long ttlMs, java.util.function.Supplier<T> loader) {
        CachedAny hit = extraCache.get(key);
        if (hit != null && System.currentTimeMillis() - hit.at() < ttlMs) {
            return (T) hit.data();
        }
        T data = loader.get();
        extraCache.put(key, new CachedAny(System.currentTimeMillis(), data));
        return data;
    }

    private static List<?> outputList(Map<String, Object> res) {
        for (String key : List.of("output", "output1", "output2")) {
            if (res.get(key) instanceof List<?> rows) return rows;
        }
        return List.of();
    }

    /** 종목 관련 뉴스 제목 (KIS 종합 시황/공시 제목, FHKST01011800). 5분 캐시. */
    public List<Map<String, Object>> news(String code) {
        return cached("news:" + code, 300_000, () -> {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("FID_NEWS_OFER_ENTP_CODE", "");
            params.put("FID_COND_MRKT_CLS_CODE", "");
            params.put("FID_INPUT_ISCD", code);
            params.put("FID_TITL_CNTT", "");
            params.put("FID_INPUT_DATE_1", "");
            params.put("FID_INPUT_HOUR_1", "");
            params.put("FID_RANK_SORT_CLS_CODE", "");
            params.put("FID_INPUT_SRNO", "");
            Map<String, Object> res = kisGet(
                    "/uapi/domestic-stock/v1/quotations/news-title", "FHKST01011800", params);

            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : outputList(res)) {
                if (!(o instanceof Map<?, ?> m)) continue;
                String title = str(m.get("hts_pbnt_titl_cntt"));
                if (title.isEmpty()) continue;
                String d = str(m.get("data_dt"));
                String t = str(m.get("data_tm"));
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("id", str(m.get("cntt_usiq_srno")).isEmpty() ? d + t + out.size() : str(m.get("cntt_usiq_srno")));
                n.put("title", title);
                n.put("source", str(m.get("dorg")));
                n.put("datetime", d.length() == 8 && t.length() >= 4
                        ? d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6, 8)
                          + "T" + t.substring(0, 2) + ":" + t.substring(2, 4) + ":00+09:00"
                        : null);
                out.add(n);
                if (out.size() >= 30) break;
            }
            return out;
        });
    }

    /** 증권사 투자의견 (KIS 국내주식 종목투자의견, FHKST663300C0). 최근 6개월, 1시간 캐시. */
    public List<Map<String, Object>> investOpinion(String code) {
        return cached("opinion:" + code, 3_600_000, () -> {
            LocalDate today = LocalDate.now(KST);
            Map<String, String> params = new LinkedHashMap<>();
            params.put("FID_COND_MRKT_DIV_CODE", "J");
            params.put("FID_COND_SCR_DIV_CODE", "16633");
            params.put("FID_INPUT_ISCD", code);
            params.put("FID_INPUT_DATE_1", today.minusMonths(6).format(YMD));
            params.put("FID_INPUT_DATE_2", today.format(YMD));
            Map<String, Object> res = kisGet(
                    "/uapi/domestic-stock/v1/quotations/invest-opinion", "FHKST663300C0", params);

            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : outputList(res)) {
                if (!(o instanceof Map<?, ?> m)) continue;
                String d = str(m.get("stck_bsop_date"));
                if (d.length() != 8) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6, 8));
                row.put("firm", str(m.get("mbcr_name")));
                row.put("opinion", str(m.get("invt_opnn")));
                row.put("previousOpinion", str(m.get("rgbf_invt_opnn")));
                long target = toLong(m.get("hts_goal_prc"));
                row.put("targetPrice", target > 0 ? target : null);
                out.add(row);
            }
            out.sort((a, b) -> ((String) b.get("date")).compareTo((String) a.get("date")));
            return out;
        });
    }
}