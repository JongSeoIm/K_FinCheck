package com.kfincheck.financial;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/stocks")
public class FinancialController {
    private final FinancialService service;
    private final FinancialEstimateService estimates;
    private final DividendService dividends;

    public FinancialController(FinancialService service, FinancialEstimateService estimates, DividendService dividends) {
        this.service = service;
        this.estimates = estimates;
        this.dividends = dividends;
    }

    @GetMapping("/{stockCode}/dividends")
    public DividendService.DividendFeed dividends(@PathVariable("stockCode") String stockCode) {
        return dividends.latest(stockCode);
    }

    @GetMapping("/{stockCode}/financial-estimates")
    public FinancialEstimateService.EstimateFeed estimates(@PathVariable("stockCode") String stockCode) {
        return estimates.latest(stockCode);
    }

    @GetMapping("/{stockCode}/financials")
    public FinancialReport annual(@PathVariable("stockCode") String stockCode,
            @RequestParam(value = "year", required = false) Integer year,
            @RequestParam(value = "quarter", required = false) Integer quarter) {
        return quarter == null ? service.annual(stockCode, year) : service.quarterly(stockCode, year, quarter);
    }

    @ExceptionHandler(FinancialException.class)
    public ResponseEntity<Map<String, String>> error(FinancialException error) {
        return ResponseEntity.status(error.status())
                .body(Map.of("code", error.code(), "error", error.getMessage()));
    }
}
