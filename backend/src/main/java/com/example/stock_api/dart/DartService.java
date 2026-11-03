package com.example.stock_api.dart;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.example.stock_api.common.ApiException;
import com.example.stock_api.common.MarketHours;

/**
 * OpenDART 단일회사 주요계정(fnlttSinglAcnt) 으로 최근 3개 사업연도의 매출액·영업이익·당기순이익을 가져옵니다.
 * 사업보고서(11011) 기준이며 연결재무제표(CFS)가 있으면 연결, 없으면 별도(OFS) 값을 씁니다. 6시간 캐시.
 */
@Service
public class DartService {

    private static final long TTL_MS = 6 * 3_600_000L;

    private final RestClient rest;
    private final String apiKey;

    private record Cached(long at, Map<String, Object> data) {}
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public DartService(@Value("${dart.base-url}") String baseUrl,
                       @Value("${dart.api-key}") String apiKey) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(8000);
        this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.apiKey = apiKey;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public Map<String, Object> annualFinancials(String corpCode) {
        if (!isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DART_NOT_CONFIGURED",
                    "OpenDART 키가 설정되지 않았습니다. application-local.properties 에 dart.api-key 를 넣으세요.");
        }
        if (corpCode == null || corpCode.isBlank()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NO_CORP_CODE", "이 종목은 OpenDART 고유번호가 등록되지 않았습니다.");
        }
        Cached hit = cache.get(corpCode);
        if (hit != null && System.currentTimeMillis() - hit.at() < TTL_MS) return hit.data();

        // 사업보고서는 보통 3월 말에 나오므로 작년 → 재작년 순서로 시도
        int thisYear = LocalDate.now(MarketHours.KST).getYear();
        for (int year = thisYear - 1; year >= thisYear - 2; year--) {
            Map<String, Object> result = fetch(corpCode, year);
            if (result != null) {
                cache.put(corpCode, new Cached(System.currentTimeMillis(), result));
                return result;
            }
        }
        throw new ApiException(HttpStatus.NOT_FOUND, "NO_FINANCIALS", "최근 사업보고서 재무 데이터가 없습니다.");
    }

    private Map<String, Object> fetch(String corpCode, int year) {
        Map<String, Object> res = rest.get()
                .uri(u -> u.path("/api/fnlttSinglAcnt.json")
                        .queryParam("crtfc_key", apiKey)
                        .queryParam("corp_code", corpCode)
                        .queryParam("bsns_year", year)
                        .queryParam("reprt_code", "11011")
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});

        if (res == null) throw new IllegalStateException("OpenDART 응답 없음");
        String status = String.valueOf(res.get("status"));
        if ("013".equals(status)) return null; // 조회된 데이터 없음
        if (!"000".equals(status)) {
            throw new IllegalStateException("OpenDART 오류(" + status + "): " + res.get("message"));
        }
        if (!(res.get("list") instanceof List<?> list) || list.isEmpty()) return null;

        boolean hasCfs = list.stream().anyMatch(o -> o instanceof Map<?, ?> m && "CFS".equals(m.get("fs_div")));
        String fsDiv = hasCfs ? "CFS" : "OFS";

        // 연도별 [매출액, 영업이익, 당기순이익]
        Map<Integer, Long[]> byYear = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) byYear.put(year - i, new Long[3]);

        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m) || !fsDiv.equals(m.get("fs_div"))) continue;
            String account = String.valueOf(m.get("account_nm")).replace(" ", "");
            int idx;
            if (account.startsWith("매출액") || account.equals("수익(매출액)") || account.equals("영업수익")) idx = 0;
            else if (account.startsWith("영업이익")) idx = 1;
            else if (account.startsWith("당기순이익")) idx = 2;
            else continue;

            String[] keys = {"thstrm_amount", "frmtrm_amount", "bfefrmtrm_amount"};
            for (int i = 0; i < 3; i++) {
                Long v = amount(m.get(keys[i]));
                Long[] row = byYear.get(year - i);
                if (v != null && row[idx] == null) row[idx] = v;
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        byYear.forEach((y, v) -> {
            if (v[0] == null && v[1] == null && v[2] == null) return;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("year", y);
            r.put("revenue", v[0]);
            r.put("operatingProfit", v[1]);
            r.put("netIncome", v[2]);
            rows.add(r);
        });
        if (rows.isEmpty()) return null;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportYear", year);
        result.put("basis", hasCfs ? "연결" : "별도");
        result.put("source", "OpenDART 사업보고서");
        result.put("rows", rows);
        return result;
    }

    static Long amount(Object v) {
        if (v == null) return null;
        String s = v.toString().replace(",", "").trim();
        if (s.isEmpty() || s.equals("-")) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
