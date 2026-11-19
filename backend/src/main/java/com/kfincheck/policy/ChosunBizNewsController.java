package com.kfincheck.policy;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChosunBizNewsController {
    private final ChosunBizNewsService service;

    public ChosunBizNewsController(ChosunBizNewsService service) {
        this.service = service;
    }

    @GetMapping("/api/stocks/{stockCode}/news")
    public ChosunBizNewsService.NewsList latest(@PathVariable("stockCode") String stockCode) {
        return service.latest(stockCode);
    }
}
