package com.example.stock_api.news;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "http://localhost:3000")
public class NewsController {

    private final NewsService news;

    public NewsController(NewsService news) {
        this.news = news;
    }

    // 종목 관련 기사: /api/kis/news/005930  (프론트의 /api/kis/* rewrites 규칙을 그대로 탑니다)
    @GetMapping("/api/kis/news/{code}")
    public ResponseEntity<?> news(@PathVariable String code) {
        try {
            return ResponseEntity.ok(news.latest(code));
        } catch (NewsService.NewsException e) {
            return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", "뉴스를 불러오지 못했습니다."));
        }
    }
}
