package com.example.stock_api.earnings;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "http://localhost:3000")
public class EarningsController {

    private final EarningsService earnings;

    public EarningsController(EarningsService earnings) {
        this.earnings = earnings;
    }

    // 최근 실적: /api/kis/earnings/005930  (프론트의 /api/kis/* rewrites 규칙을 그대로 탑니다)
    @GetMapping("/api/kis/earnings/{code}")
    public ResponseEntity<?> earnings(@PathVariable String code) {
        try {
            return ResponseEntity.ok(earnings.latest(code));
        } catch (EarningsService.EarningsException e) {
            return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", "실적을 불러오지 못했습니다."));
        }
    }
}
