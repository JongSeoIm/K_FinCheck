package com.example.stock_api.chart;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChartController {

    private final ChartService service;

    public ChartController(ChartService service) {
        this.service = service;
    }

    // /api/chart/005930?interval=1s|5s|30s|1m|3m|5m|10m|15m|30m|60m|D|W|M|Y
    @GetMapping("/api/chart/{code}")
    public ResponseEntity<?> chart(@PathVariable String code, @RequestParam(defaultValue = "1m") String interval) {
        try {
            return ResponseEntity.ok(service.bars(code, interval));
        } catch (ChartService.ChartException e) {
            return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", "차트 데이터를 불러오지 못했습니다."));
        }
    }
}
