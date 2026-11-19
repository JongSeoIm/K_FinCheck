package com.kfincheck.market;

import com.kfincheck.financial.DisclosureService;
import com.kfincheck.financial.FinancialException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * AI 서버가 날짜로 조회하는 API. 둘 다 DB 에서 읽는다.
 * <ul>
 *   <li>GET /api/stocks/{code}/daily-price?date=2026-11-06 : 그날 일봉 종가와 직전 거래일 종가</li>
 *   <li>GET /api/stocks/{code}/disclosures?from=2026-11-06&amp;to=2026-11-06 : 기간 안의 공시 목록</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/stocks")
public class MarketDataController {
    private final DailyPriceService prices;
    private final DisclosureService disclosures;

    public MarketDataController(DailyPriceService prices, DisclosureService disclosures) {
        this.prices = prices;
        this.disclosures = disclosures;
    }

    @GetMapping("/{stockCode}/daily-price")
    public DailyPriceService.DailyClose dailyPrice(@PathVariable("stockCode") String stockCode,
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return prices.close(stockCode, date);
    }

    @GetMapping("/{stockCode}/disclosures")
    public DisclosureService.Result disclosures(@PathVariable("stockCode") String stockCode,
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return disclosures.list(stockCode, from, to);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", error.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException error) {
        return body(HttpStatus.NOT_FOUND, "NO_DATA", error.getMessage());
    }

    @ExceptionHandler(FinancialException.class)
    public ResponseEntity<Map<String, String>> dart(FinancialException error) {
        return body(error.status(), error.code(), error.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> unavailable(IllegalStateException error) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_UNAVAILABLE", "자료를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", code, "error", message == null ? "" : message));
    }
}
