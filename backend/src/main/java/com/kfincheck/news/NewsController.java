package com.kfincheck.news;

import com.kfincheck.auth.AccountService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/stocks")
public class NewsController {
    private final NewsService service;
    public NewsController(NewsService service) { this.service = service; }

    /** 조선비즈 RSS 목록(최신순) + 선택 종목 기준 제목 관련도 구분 */
    @GetMapping("/{stockCode}/news")
    public NewsService.NewsFeed news(@PathVariable("stockCode") String stockCode) {
        return service.feed(stockCode);
    }

    /**
     * 뉴스 목록의 'AI 요약' 버튼: 로그인 사용자가 고른 기사 1건의 일회성 요약(SecurityConfig에서 로그인 필요).
     * AI 대화의 기사 요약과 같은 처리(원문 1회 요청·요약문 DB 24시간 보관·같은 하루 20건 제한)를 쓴다.
     */
    @PostMapping("/{stockCode}/news/summary")
    public NewsService.Summary summary(@PathVariable("stockCode") String stockCode,
                                       @RequestBody SummaryRequest request, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new NewsService.NewsException(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "로그인하면 AI 요약을 볼 수 있습니다.");
        }
        if (request == null || request.link() == null || request.link().isBlank() || request.link().length() > 500) {
            throw new NewsService.NewsException(HttpStatus.BAD_REQUEST, "INVALID_LINK", "요약할 기사 링크가 필요합니다.");
        }
        return service.summarize(stockCode, request.link(), userKey(authentication));
    }

    /** AI 대화와 같은 사용자 키를 써서 하루 20건 제한을 버튼·대화가 함께 나눠 쓰게 한다. */
    static String userKey(Authentication authentication) {
        return authentication.getPrincipal() instanceof AccountService.User user
                ? "user-" + user.userId() : authentication.getName();
    }

    public record SummaryRequest(String link) { }

    @ExceptionHandler(NewsService.NewsException.class)
    public ResponseEntity<Map<String, String>> error(NewsService.NewsException error) {
        return ResponseEntity.status(error.status()).body(Map.of("code", error.code(), "error", error.getMessage()));
    }
}
