package com.kfincheck.controller;

import com.kfincheck.dto.StockCandleDto;
import com.kfincheck.dto.StockPriceDto;
import com.kfincheck.service.StockService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stocks")
public class StockController {

    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    @GetMapping("/{stockCode}/price")
    public ResponseEntity<StockPriceDto> getStockPrice(@PathVariable("stockCode") String stockCode) {
        StockPriceDto price = stockService.getStockPrice(stockCode);
        return ResponseEntity.ok(price);
    }

    @GetMapping("/{stockCode}/candles")
    public ResponseEntity<List<StockCandleDto>> getDailyCandles(
            @PathVariable("stockCode") String stockCode,
            @RequestParam(value = "days", defaultValue = "40") int days,
            @RequestParam(value = "period", defaultValue = "D") String period
    ) {
        List<StockCandleDto> candles = stockService.getDailyCandles(stockCode, days, period);
        return ResponseEntity.ok(candles);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleServiceError(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
    }
}
