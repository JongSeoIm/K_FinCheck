package com.example.stock_api.quote;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "http://localhost:3000")
public class TradeTimeController {

    private final TradeTimeService service;

    public TradeTimeController(TradeTimeService service) {
        this.service = service;
    }

    // 최근 체결 시각: /api/kis/trade-time/005930  (프론트의 /api/kis/* rewrites 규칙을 그대로 탑니다)
    @GetMapping("/api/kis/trade-time/{code}")
    public ResponseEntity<?> tradeTime(@PathVariable String code) {
        if (!code.matches("\\d{6}")) {
            return ResponseEntity.badRequest().body(Map.of("error", "종목 코드는 6자리 숫자입니다."));
        }
        try {
            return ResponseEntity.ok(service.latest(code));
        } catch (Exception e) {
            return ResponseEntity.status(404).body(Map.of("error", "체결 시각을 확인할 수 없습니다."));
        }
    }
}
