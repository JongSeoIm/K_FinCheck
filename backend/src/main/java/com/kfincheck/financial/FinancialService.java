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
import java.util.function.Supplier;

@Service
public class FinancialService {
    private static final Map<String, Company> COMPANIES = Map.of(
            "005930", new Company("삼성전자", "00126380"),
            "000660", new Company("SK하이닉스", "00164779"),
            "035420", new Company("NAVER", "00266961"),
            "035720", new Company("카카오", "00258801"),
            "005380", new Company("현대차", "00164742"),
            "000270", new Company("기아", "00106641"),
            "051910", new Company("LG화학", "00356361"),
            "006400", new Company("삼성SDI", "00126362"));
    record Company(String name, String dartCode) { }

    static Company company(String stockCode) {
        Company company = COMPANIES.get(stockCode);
        if (company == null) {
            throw new FinancialException(HttpStatus.NOT_FOUND, "UNSUPPORTED_STOCK",
                    "이 종목의 연결 실적은 지원하지 않습니다.");
        }
        return company;
    }
    private final OpenDartClient client;
    private final FinancialRepository repository;

    public FinancialService(OpenDartClient client, FinancialRepository repository) {
        this.client = client;
        this.repository = repository;
    }

    public synchronized FinancialReport annual(String stockCode, Integer requestedYear) {
        String corp = company(stockCode).dartCode();
        int currentYear = LocalDate.now(ZoneId.of("Asia/Seoul")).getYear();
        int year = requestedYear == null ? currentYear - 1 : requestedYear;
        if (year < 2015 || year >= currentYear) {
            throw new FinancialException(HttpStatus.BAD_REQUEST, "INVALID_YEAR",
                    "사업연도는 2015년부터 전년도까지 선택할 수 있습니다.");
        }
        return load(stockCode, corp, year, "11011", () -> client.annualReport(corp, year));
    }

    public synchronized FinancialReport quarterly(String stockCode, Integer year, int quarter) {
        String corp = company(stockCode).dartCode();
        String reportCode = switch (quarter) {
            case 1 -> "11013";
            case 2 -> "11012";
            case 3 -> "11014";
            default -> throw new FinancialException(HttpStatus.BAD_REQUEST, "INVALID_QUARTER",
                    "분기 실적은 1분기부터 3분기까지 선택할 수 있습니다.");
        };
        int currentYear = LocalDate.now(ZoneId.of("Asia/Seoul")).getYear();
        if (year == null || year < 2015 || year > currentYear) {
            throw new FinancialException(HttpStatus.BAD_REQUEST, "INVALID_YEAR",
                    "사업연도는 2015년부터 올해까지 선택할 수 있습니다.");
        }
        return load(stockCode, corp, year, reportCode, () -> client.financialReport(corp, year, reportCode));
    }

    private FinancialReport load(String stockCode, String corp, int year, String reportCode, Supplier<JsonNode> fetch) {
        try {
            FinancialReport saved = repository.latest(stockCode, year, reportCode);
            if (saved != null && saved.fetchedAt().plusSeconds(3600).isAfter(Instant.now())) return saved;
            FinancialReport report = normalize(stockCode, corp, year, reportCode, fetch.get());
            repository.save(report);
            return repository.latest(stockCode, year, reportCode);
        } catch (org.springframework.dao.DataAccessException error) {
            throw new FinancialException(HttpStatus.SERVICE_UNAVAILABLE, "FINANCIAL_DB_UNAVAILABLE",
                    "실적 저장소에 연결하지 못했습니다. DB 설정을 확인해 주세요.");
        }
    }

    static FinancialReport normalize(String stock, String corp, int year, String reportCode, JsonNode rows) {
        // 분기·반기 손익계산서에서 전년 같은 3개월은 전기 분기 금액에 있다.
        String previousField = "11011".equals(reportCode) ? "frmtrm_amount" : "frmtrm_q_amount";
        var metrics = List.of(
                metric(rows, previousField, "revenue", "매출액", List.of("ifrs-full_Revenue", "ifrs_Revenue"),
                        List.of("매출액", "수익(매출액)", "영업수익")),
                metric(rows, previousField, "operatingProfit", "영업이익", List.of("dart_OperatingIncomeLoss"),
                        List.of("영업이익", "영업이익(손실)", "영업손익")),
                metric(rows, previousField, "netIncome", "당기순이익", List.of("ifrs-full_ProfitLoss", "ifrs_ProfitLoss"),
                        List.of("당기순이익", "당기순이익(손실)", "당기순손익")));
        String receipt = "";
        for (JsonNode row : rows) {
            if (row.path("rcept_no").asText().matches("\\d{14}")) {
                receipt = row.path("rcept_no").asText();
                break;
            }
        }
        return new FinancialReport(stock, corp, year, year - 1, reportCode, "CFS", "KRW", receipt,
                receipt.isEmpty() ? null : "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + receipt,
                Instant.now(), metrics);
    }

    private static FinancialReport.Metric metric(JsonNode rows, String previousField, String key, String label,
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
                if (matches.stream().anyMatch(other -> !sameAmounts(row, other, previousField))) {
                    return new FinancialReport.Metric(key, label, null, null, null, "UNAVAILABLE", "AMBIGUOUS", null, null, "KRW");
                }
                if (!"KRW".equals(row.path("currency").asText())) {
                    return new FinancialReport.Metric(key, label, null, null, null, "UNAVAILABLE", "UNSUPPORTED_CURRENCY", row.path("account_id").asText(), row.path("account_nm").asText(), row.path("currency").asText());
                }
                BigDecimal current = amount(row.path("thstrm_amount").asText());
                BigDecimal previous = amount(row.path(previousField).asText());
                return metricValue(key, label, current, previous, current == null ? "MISSING" : "AVAILABLE",
                        row.path("account_id").asText(), row.path("account_nm").asText(), "KRW");
            }
        }
        return new FinancialReport.Metric(key, label, null, null, null, "UNAVAILABLE", "MISSING", null, null, "KRW");
    }

    static FinancialReport.Metric metricValue(String key, String label, BigDecimal current, BigDecimal previous,
            String status, String sourceId, String sourceName, String currency) {
        BigDecimal rate = current != null && previous != null && previous.signum() > 0
                ? current.subtract(previous).multiply(BigDecimal.valueOf(100))
                        .divide(previous, 2, RoundingMode.HALF_UP) : null;
        String comparison = rate != null ? "RATE" : "UNAVAILABLE";
        if (current != null && previous != null) {
            if (current.signum() > 0 && previous.signum() < 0) comparison = "TURNAROUND";
            else if (current.signum() < 0 && previous.signum() >= 0) comparison = "TURNED_LOSS";
            else if (current.signum() < 0 && previous.signum() < 0) comparison = "CONTINUED_LOSS";
        }
        return new FinancialReport.Metric(key, label, current == null ? null : current.stripTrailingZeros().toPlainString(),
                previous == null ? null : previous.stripTrailingZeros().toPlainString(), rate, comparison, status,
                sourceId, sourceName, currency);
    }

    private static boolean sameAmounts(JsonNode first, JsonNode second, String previousField) {
        return first.path("thstrm_amount").equals(second.path("thstrm_amount"))
                && first.path(previousField).equals(second.path(previousField))
                && first.path("currency").equals(second.path("currency"));
    }

    private static BigDecimal amount(String value) {
        String cleaned = value.replace(",", "").trim();
        if (!cleaned.matches("-?\\d+(\\.\\d+)?")) return null;
        return new BigDecimal(cleaned);
    }
}
