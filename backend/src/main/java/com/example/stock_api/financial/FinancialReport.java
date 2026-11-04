package com.example.stock_api.financial;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

/** financial_report 한 행 */
@Getter
@Setter
public class FinancialReport {
    private Long reportId;
    private String receiptNo;
    private String stockCode;
    private Integer businessYear;
    private String reportCode;
    private String fsDiv;
    private LocalDateTime collectedAt;
}