package com.kfincheck.financial;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

@Repository
public class FinancialRepository {
    private static final Map<String, String> CODES = Map.of("revenue", "REVENUE", "operatingProfit", "OPERATING_PROFIT", "netIncome", "NET_INCOME");
    private final FinancialMapper mapper;
    public FinancialRepository(FinancialMapper mapper) { this.mapper = mapper; }

    @Transactional(readOnly = true)
    public FinancialReport latest(String stock, int year) {
        var header = mapper.latest(stock, year);
        if (header == null) return null;
        var stored = mapper.values(header.reportId());
        var metrics = List.of("revenue", "operatingProfit", "netIncome").stream().map(key -> {
            var value = stored.stream().filter(row -> CODES.get(key).equals(row.accountCode())).findFirst();
            return value.map(row -> FinancialService.metricValue(key, row.accountName(), row.currentAmount(), row.previousAmount(),
                    row.valueStatus(), row.sourceAccountId(), row.sourceAccountName(), row.currency()))
                    .orElseGet(() -> FinancialService.metricValue(key, label(key), null, null, "MISSING", null, null, "KRW"));
        }).toList();
        return new FinancialReport(stock, header.corpCode(), year, year - 1, header.reportCode(), header.fsDiv(), "KRW",
                header.receiptNo(), "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + header.receiptNo(),
                header.collectedAt().toInstant(ZoneOffset.UTC), metrics);
    }

    @Transactional
    public void save(FinancialReport report) {
        if (!report.receiptNo().matches("\\d{14}")) throw new FinancialException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                "INVALID_REPORT", "공시 접수번호를 확인할 수 없어 저장하지 않았습니다.");
        String name = switch (report.stockCode()) { case "005930" -> "삼성전자"; case "000660" -> "SK하이닉스";
            default -> throw new IllegalArgumentException("지원 기업 확인 필요"); };
        mapper.upsertCompany(report.stockCode(), name, report.corpCode());
        mapper.upsertReport(report, LocalDateTime.ofInstant(report.fetchedAt(), ZoneOffset.UTC));
        var header = mapper.byReceipt(report.receiptNo(), report.fsDiv());
        if (!report.stockCode().equals(header.stockCode()) || report.businessYear() != header.businessYear())
            throw new IllegalStateException("공시와 기업·연도 연결 확인 필요");
        for (var metric : report.metrics()) mapper.upsertValue(header.reportId(), CODES.get(metric.key()), metric);
    }

    private static String label(String key) { return switch (key) { case "revenue" -> "매출액"; case "operatingProfit" -> "영업이익"; default -> "당기순이익"; }; }
}
