package com.example.stock_api.opinion;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "http://localhost:3000")
public class OpinionController {

    private final OpinionService opinions;

    public OpinionController(OpinionService opinions) {
        this.opinions = opinions;
    }

    // 투자의견: /api/kis/opinion/005930   (점검용 원본: /api/kis/opinion/005930?raw=true)
    @GetMapping("/api/kis/opinion/{code}")
    public ResponseEntity<?> opinion(@PathVariable String code,
                                     @RequestParam(defaultValue = "false") boolean raw) {
        if (!code.matches("\\d{6}")) {
            return ResponseEntity.status(404).body(Map.of("error", "국내 종목코드(6자리)만 지원합니다."));
        }
        try {
            return ResponseEntity.ok(raw ? opinions.raw(code) : opinions.summary(code));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", "투자의견을 불러오지 못했습니다."));
        }
    }
}
