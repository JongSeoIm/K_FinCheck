package com.example.stock_api.kis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;

@RestController
@CrossOrigin(origins = "http://localhost:3000")
public class KisController {

    private final KisService kis;

    public KisService getKis() {
        return kis;
    }

    public KisController(KisService kis) {
        this.kis = kis;
    }

    @GetMapping("/api/kis/price/{code}")
    public ResponseEntity<?> price(@PathVariable String code) {
        try {
            return ResponseEntity.ok(kis.currentPrice(code));
        } catch (RestClientResponseException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(Map.of("error", e.getResponseBodyAsString()));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    // 여러 종목 한 번에 조회 (상단 티커용): /api/kis/prices?codes=005930,000660
    @GetMapping("/api/kis/prices")
    public ResponseEntity<?> prices(@RequestParam List<String> codes) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String code : codes) {
            try {
                result.put(code, kis.currentPrice(code));
            } catch (Exception e) {
                result.put(code, Map.of("error", String.valueOf(e.getMessage())));
            }
        }
        return ResponseEntity.ok(result);
    }

    // 차트 데이터: /api/kis/candles/005930?range=1D|1W|1M|1Y
    @GetMapping("/api/kis/candles/{code}")
    public ResponseEntity<?> candles(@PathVariable String code,
                                     @RequestParam(defaultValue = "1D") String range) {
        try {
            return ResponseEntity.ok("1D".equals(range)
                    ? kis.minuteCandles(code)
                    : kis.dailyCandles(code, range));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    // 종목별 프로필 및 실시간 시가총액 API
    @GetMapping({"/api/stock/{symbol}/profile", "/api/kis/profile/{symbol}"})
    public ResponseEntity<?> profile(@PathVariable String symbol) {
        try {
            // KisService를 통해 실시간 시세 및 계산된 시가총액 가져오기
            Map<String, Object> liveData = kis.currentPrice(symbol);
            Object marketCap = liveData.get("marketCapitalization");

            String name = symbol;
            String industry = "국내주식";

            // 종목 코드별 이름 및 업종 매핑
            if ("005930".equals(symbol)) {
                name = "삼성전자";
                industry = "전기·전자";
            } else if ("005380".equals(symbol)) {
                name = "현대차";
                industry = "자동차";
            } else if ("000660".equals(symbol)) {
                name = "SK하이닉스";
                industry = "전기·전자";
            } else if ("035420".equals(symbol)) {
                name = "NAVER";
                industry = "서비스업";
            } else if ("035720".equals(symbol)) {
                name = "카카오";
                industry = "서비스업";
            } else if ("000270".equals(symbol)) {
                name = "기아";
                industry = "자동차";
            } else if ("051910".equals(symbol)) {
                name = "LG화학";
                industry = "화학";
            } else if ("006400".equals(symbol)) {
                name = "삼성SDI";
                industry = "전기·전자";
            }

            return ResponseEntity.ok(Map.of(
                "name", name,
                "exchange", "KRX",
                "industry", industry,
                "marketCapitalization", marketCap != null ? marketCap : 0L
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                "name", symbol,
                "exchange", "KRX",
                "industry", "국내주식",
                "marketCapitalization", 0L
            ));
        }
    }
}