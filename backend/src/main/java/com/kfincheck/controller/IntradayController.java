package com.kfincheck.controller;

import com.kfincheck.service.IntradayService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.Map;

@RestController
@RequestMapping("/api/stocks")
public class IntradayController {
    private final IntradayService service;
    public IntradayController(IntradayService service) { this.service=service; }
    @GetMapping("/{stockCode}/intraday")
    public IntradayService.Page history(@PathVariable String stockCode,@RequestParam(required=false) String before) {
        return service.history(stockCode,before);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> invalid(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error",e.getMessage())); }
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<?> unavailable(IllegalStateException e) { return ResponseEntity.status(503).body(Map.of("error","과거 분봉을 조회하지 못했습니다. 잠시 후 다시 시도해 주세요.")); }
}
