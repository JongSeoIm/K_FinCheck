package com.kfincheck.news;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/stocks")
public class NewsController {
    private final NewsService service;
    public NewsController(NewsService service) { this.service = service; }

    @GetMapping("/{stockCode}/news")
    public NewsService.NewsFeed news(@PathVariable("stockCode") String stockCode) {
        return service.latest(stockCode);
    }

    @ExceptionHandler(NewsService.NewsException.class)
    public ResponseEntity<Map<String, String>> error(NewsService.NewsException error) {
        return ResponseEntity.status(error.status())
                .body(Map.of("code", error.code(), "error", error.getMessage()));
    }
}
