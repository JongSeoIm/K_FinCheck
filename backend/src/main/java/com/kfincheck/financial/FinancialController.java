package com.kfincheck.financial;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/stocks")
public class FinancialController {
    private final FinancialService service;

    public FinancialController(FinancialService service) { this.service = service; }

    @GetMapping("/{stockCode}/financials")
    public FinancialReport annual(@PathVariable("stockCode") String stockCode,
            @RequestParam(value = "year", required = false) Integer year) {
        return service.annual(stockCode, year);
    }

    @ExceptionHandler(FinancialException.class)
    public ResponseEntity<Map<String, String>> error(FinancialException error) {
        return ResponseEntity.status(error.status())
                .body(Map.of("code", error.code(), "error", error.getMessage()));
    }
}
