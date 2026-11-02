package com.kfincheck.financial;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class FinancialService {
    private static final Map<String, String> COMPANIES = Map.of(
            "005930", "00126380", "000660", "00164779");
    private final OpenDartClient client;
    // 최초 두 기업만 지원한다. 동시 요청도 같은 공시를 중복 수집하지 않게 직렬화한다.
    private final Map<String, FinancialReport> cache = new java.util.HashMap<>();

    public FinancialService(OpenDartClient client) { this.client = client; }

    public synchronized FinancialReport annual(String stockCode, Integer requestedYear) {
        String corp = COMPANIES.get(stockCode);
        if (corp == null) {
            throw new FinancialException(HttpStatus.NOT_FOUND, "UNSUPPORTED_STOCK",
                    "현재 연간 실적은 삼성전자·SK하이닉스를 지원합니다.");
        }
        int currentYear = LocalDate.now(ZoneId.of("Asia/Seoul")).getYear();
        int year = requestedYear == null ? currentYear - 1 : requestedYear;
        if (year < 2015 || year >= currentYear) {
            throw new FinancialException(HttpStatus.BAD_REQUEST, "INVALID_YEAR",
                    "사업연도는 2015년부터 전년도까지 선택할 수 있습니다.");
        }
        String key = stockCode + ":" + year;
        FinancialReport cached = cache.get(key);
        if (cached != null && cached.fetchedAt().plusSeconds(3600).isAfter(Instant.now())) return cached;
        FinancialReport report = normalize(stockCode, corp, year, client.annualReport(corp, year));
        // 최대 두 기업 × 지원 연도 수만큼 보관하며 성공 응답만 한 시간 재사용한다.
        cache.put(key, report);
        return report;
    }

    static FinancialReport normalize(String stock, String corp, int year, JsonNode rows) {
        var metrics = List.of(
                metric(rows, "revenue", "매출액", List.of("ifrs-full_Revenue", "ifrs_Revenue"),
                        List.of("매출액", "수익(매출액)", "영업수익")),
                metric(rows, "operatingProfit", "영업이익", List.of("dart_OperatingIncomeLoss"),
                        List.of("영업이익", "영업이익(손실)", "영업손익")),
                metric(rows, "netIncome", "당기순이익", List.of("ifrs-full_ProfitLoss", "ifrs_ProfitLoss"),
                        List.of("당기순이익", "당기순이익(손실)", "당기순손익")));
        String receipt = "";
        for (JsonNode row : rows) {
            if (row.path("rcept_no").asText().matches("\\d{14}")) {
                receipt = row.path("rcept_no").asText();
                break;
            }
        }
        return new FinancialReport(stock, corp, year, year - 1, "11011", "CFS", "KRW", receipt,
                receipt.isEmpty() ? null : "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + receipt,
                Instant.now(), metrics);
    }

    private static FinancialReport.Metric metric(JsonNode rows, String key, String label,
            List<String> ids, List<String> names) {
        // 표준 코드 우선. IS가 없을 때만 CIS를 사용하며 이름 매핑은 그 다음에 적용한다.
        for (boolean byId : List.of(true, false)) {
            for (String statement : List.of("IS", "CIS")) {
                List<JsonNode> matches = new ArrayList<>();
                for (JsonNode row : rows) {
                    if (statement.equals(row.path("sj_div").asText())
                            && (byId ? ids.contains(row.path("account_id").asText())
                                    : names.contains(row.path("account_nm").asText().replace(" ", "")))) {
                        matches.add(row);
                    }
                }
                if (matches.isEmpty()) continue;
                JsonNode row = matches.getFirst();
                // 같은 계정 후보가 서로 다른 값이면 임의로 하나를 선택하지 않는다.
                if (matches.stream().anyMatch(other -> !sameAmounts(row, other))) {
                    return new FinancialReport.Metric(key, label, null, null, null, "UNAVAILABLE", "AMBIGUOUS");
                }
                if (!"KRW".equals(row.path("currency").asText())) {
                    return new FinancialReport.Metric(key, label, null, null, null, "UNAVAILABLE", "UNSUPPORTED_CURRENCY");
                }
                BigDecimal current = amount(row.path("thstrm_amount").asText());
                BigDecimal previous = amount(row.path("frmtrm_amount").asText());
                BigDecimal rate = current != null && previous != null && previous.signum() > 0
                        ? current.subtract(previous).multiply(BigDecimal.valueOf(100))
                                .divide(previous, 2, RoundingMode.HALF_UP) : null;
                String comparison = rate != null ? "RATE" : "UNAVAILABLE";
                if (current != null && previous != null) {
                    if (current.signum() > 0 && previous.signum() < 0) comparison = "TURNAROUND";
                    else if (current.signum() < 0 && previous.signum() >= 0) comparison = "TURNED_LOSS";
                    else if (current.signum() < 0 && previous.signum() < 0) comparison = "CONTINUED_LOSS";
                }
                return new FinancialReport.Metric(key, label, current == null ? null : current.toPlainString(),
                        previous == null ? null : previous.toPlainString(), rate, comparison,
                        current == null ? "MISSING" : "AVAILABLE");
            }
        }
        return new FinancialReport.Metric(key, label, null, null, null, "UNAVAILABLE", "MISSING");
    }

    private static boolean sameAmounts(JsonNode first, JsonNode second) {
        return first.path("thstrm_amount").equals(second.path("thstrm_amount"))
                && first.path("frmtrm_amount").equals(second.path("frmtrm_amount"))
                && first.path("currency").equals(second.path("currency"));
    }

    private static BigDecimal amount(String value) {
        String cleaned = value.replace(",", "").trim();
        if (!cleaned.matches("-?\\d+(\\.\\d+)?")) return null;
        return new BigDecimal(cleaned);
    }
}
