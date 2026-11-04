package com.example.stock_api.dart;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.example.stock_api.common.ApiException;
import com.example.stock_api.common.MarketHours;

/**
 * OpenDART 단일회사 전체 재무제표(fnlttSinglAcntAll)에서 연결 사업보고서(11011)의 매출액·영업이익·당기순이익을 가져옵니다.
 * 표준 계정 코드를 먼저 찾고 없으면 계정명으로 찾습니다. 손익계산서(IS)가 없을 때만 포괄손익계산서(CIS)를 씁니다.
 * 받은 자료의 재사용 기간은 DB 수집 시각으로 관리합니다 (FinancialService).
 */
@Service
public class DartService {

    private static final String REPORT_CODE = "11011"; // 사업보고서
    private static final String FS_DIV = "CFS"; // 연결재무제표

    /** 수집한 계정 하나. 금액을 확정할 수 없으면 null 이고 status 로 이유를 구분합니다 */
    public record Account(String code, String name, Long current, Long previous, String currency,
                          String sourceId, String sourceName, String statement, String status) {}

    private record AccountRule(String code, String name, Set<String> ids, Set<String> names) {}

    private static final List<AccountRule> RULES = List.of(
            new AccountRule("REVENUE", "매출액",
                    Set.of("ifrs-full_Revenue", "ifrs_Revenue"),
                    Set.of("매출액", "수익(매출액)", "영업수익")),
            new AccountRule("OPERATING_PROFIT", "영업이익",
                    Set.of("dart_OperatingIncomeLoss"),
                    Set.of("영업이익", "영업이익(손실)", "영업손익")),
            new AccountRule("NET_INCOME", "당기순이익",
                    Set.of("ifrs-full_ProfitLoss", "ifrs_ProfitLoss"),
                    Set.of("당기순이익", "당기순이익(손실)", "당기순손익")));

    private final RestClient rest;
    private final String apiKey;

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
        requireReady(corpCode);

        // 사업보고서는 보통 3월 말에 나오므로 작년 → 재작년 순서로 시도
        int thisYear = LocalDate.now(MarketHours.KST).getYear();
        for (int year = thisYear - 1; year >= thisYear - 2; year--) {
            Map<String, Object> result = fetch(corpCode, year);
            if (result != null) return result;
        }
        throw new ApiException(HttpStatus.NOT_FOUND, "NO_FINANCIALS", "최근 사업보고서 재무 데이터가 없습니다.");
    }

    /** 특정 사업연도의 사업보고서. 없으면 null */
    public Map<String, Object> annualFinancials(String corpCode, int year) {
        requireReady(corpCode);
        return fetch(corpCode, year);
    }

    private void requireReady(String corpCode) {
        if (!isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DART_NOT_CONFIGURED",
                    "OpenDART 키가 설정되지 않았습니다. application-local.properties 에 dart.api-key 를 넣으세요.");
        }
        if (corpCode == null || corpCode.isBlank()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NO_CORP_CODE", "이 종목은 OpenDART 고유번호가 등록되지 않았습니다.");
        }
    }

    private Map<String, Object> fetch(String corpCode, int year) {
        Map<String, Object> res = rest.get()
                .uri(u -> u.path("/api/fnlttSinglAcntAll.json")
                        .queryParam("crtfc_key", apiKey)
                        .queryParam("corp_code", corpCode)
                        .queryParam("bsns_year", year)
                        .queryParam("reprt_code", REPORT_CODE)
                        .queryParam("fs_div", FS_DIV)
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

        List<Map<?, ?>> rows = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> row) rows.add(row);
        }
        List<Account> accounts = RULES.stream().map(rule -> account(rows, rule)).toList();
        if (accounts.stream().allMatch(a -> "MISSING".equals(a.status()))) return null;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportYear", year);
        result.put("fsDiv", FS_DIV);
        result.put("reportCode", REPORT_CODE);
        result.put("receiptNo", receiptNo(rows));
        result.put("accounts", accounts);
        return result;
    }

    private static String receiptNo(List<Map<?, ?>> rows) {
        for (Map<?, ?> row : rows) {
            String value = text(row.get("rcept_no"));
            if (value != null) return value;
        }
        return null;
    }

    private static Account account(List<Map<?, ?>> rows, AccountRule rule) {
        List<Map<?, ?>> found = candidates(rows, rule);
        if (found.isEmpty()) return empty(rule, "KRW", null, null, null, "MISSING");

        Map<?, ?> first = found.get(0);
        String id = text(first.get("account_id"));
        String name = text(first.get("account_nm"));
        String statement = text(first.get("sj_div"));
        String currency = text(first.get("currency")) == null ? "KRW" : text(first.get("currency"));

        // 같은 계정 후보의 값이 다르면 하나를 임의로 고르지 않습니다
        boolean ambiguous = found.stream().anyMatch(row ->
                !Objects.equals(amount(row.get("thstrm_amount")), amount(first.get("thstrm_amount")))
                        || !Objects.equals(amount(row.get("frmtrm_amount")), amount(first.get("frmtrm_amount")))
                        || !Objects.equals(text(row.get("currency")), text(first.get("currency"))));
        if (ambiguous) return empty(rule, currency, id, name, statement, "AMBIGUOUS");
        if (!"KRW".equals(currency)) return empty(rule, currency, id, name, statement, "UNSUPPORTED_CURRENCY");

        Long current = amount(first.get("thstrm_amount"));
        Long previous = amount(first.get("frmtrm_amount"));
        return new Account(rule.code(), rule.name(), current, previous, currency, id, name, statement,
                amountStatus(current, previous));
    }

    /** 찾는 순서: 손익계산서 코드 → 포괄손익계산서 코드 → 손익계산서 이름 → 포괄손익계산서 이름 */
    private static List<Map<?, ?>> candidates(List<Map<?, ?>> rows, AccountRule rule) {
        for (boolean useId : List.of(true, false)) {
            for (String statement : List.of("IS", "CIS")) {
                List<Map<?, ?>> found = rows.stream()
                        .filter(row -> statement.equals(text(row.get("sj_div"))))
                        .filter(row -> useId ? rule.ids().contains(String.valueOf(row.get("account_id")))
                                : rule.names().contains(String.valueOf(row.get("account_nm")).replace(" ", "")))
                        .toList();
                if (!found.isEmpty()) return found;
            }
        }
        return List.of();
    }

    private static Account empty(AccountRule rule, String currency, String id, String name, String statement, String status) {
        return new Account(rule.code(), rule.name(), null, null, currency, id, name, statement, status);
    }

    static String amountStatus(Long current, Long previous) {
        if (current != null && previous != null) return "AVAILABLE";
        return current == null && previous == null ? "MISSING" : "PARTIAL";
    }

    private static String text(Object v) {
        return v == null || v.toString().isBlank() ? null : v.toString();
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