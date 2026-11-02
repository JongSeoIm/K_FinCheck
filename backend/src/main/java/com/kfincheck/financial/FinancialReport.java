package com.kfincheck.financial;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record FinancialReport(String stockCode, String corpCode, int businessYear,
        int previousYear, String reportCode, String fsDiv, String currency,
        String receiptNo, String sourceUrl, Instant fetchedAt, List<Metric> metrics) {
    // 원 단위 금액을 문자열로 전달해 JavaScript 정수 정밀도 손실을 방지한다.
    public record Metric(String key, String label, String currentAmount, String previousAmount,
            BigDecimal growthRate, String comparison, String status) { }
}
