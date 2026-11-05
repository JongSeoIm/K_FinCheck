package com.example.stock_api.realtime;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 실시간 체결 수신 상태 확인용. (KIS 연결, Kafka, 종목별 마지막 체결 수신 시각) */
@RestController
public class RealtimeController {

    private final RealtimeStatus status;
    private final List<String> symbols;

    public RealtimeController(RealtimeStatus status,
                              @org.springframework.beans.factory.annotation.Value("${stream.symbols:005930,000660}") List<String> symbols) {
        this.status = status;
        this.symbols = symbols;
    }

    @GetMapping("/api/realtime/status")
    public Map<String, Object> status() {
        return status.view(symbols);
    }
}
