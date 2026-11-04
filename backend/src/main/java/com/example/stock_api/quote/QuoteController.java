package com.example.stock_api.quote;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Polling 용 최신 시세. 서버 메모리(QuoteStore)에서만 읽고 KIS 를 호출하지 않습니다. */
@RestController
public class QuoteController {

    private final QuoteStore store;

    public QuoteController(QuoteStore store) {
        this.store = store;
    }

    // /api/quote/state?codes=005930,000660  (codes 를 생략하면 지원 종목 전체)
    @GetMapping("/api/quote/state")
    public Map<String, Object> state(@RequestParam(required = false) List<String> codes) {
        List<String> wanted = new ArrayList<>();
        if (codes == null || codes.isEmpty()) {
            wanted.addAll(SupportedStocks.CODES);
        } else {
            for (String code : codes) {
                if (SupportedStocks.contains(code)) {
                    wanted.add(code);
                }
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serverTime", Instant.now().toString());
        body.put("quotes", store.snapshot(wanted));
        return body;
    }
}
