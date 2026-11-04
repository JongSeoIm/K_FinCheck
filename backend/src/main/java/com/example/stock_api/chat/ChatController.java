package com.example.stock_api.chat;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.stock_api.auth.AuthUser;

/** AI 챗봇 API. 로그인한 사용자만 호출할 수 있습니다. (SecurityConfig 에서 /api/chat/** 보호) */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    public record AskRequest(String message, String stockCode) {}

    private final ChatService service;

    public ChatController(ChatService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<?> ask(@AuthenticationPrincipal AuthUser user, @RequestBody AskRequest body) {
        try {
            return ResponseEntity.ok(service.ask(user.userId(), body.message(), body.stockCode()));
        } catch (ChatService.ChatException e) {
            return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "답변을 만들지 못했습니다."));
        }
    }

    /** 창을 다시 열 때 최근 대화(최대 10개)를 불러옵니다. */
    @GetMapping("/history")
    public List<ChatMemory.Message> history(@AuthenticationPrincipal AuthUser user) {
        return service.history(user.userId());
    }

    /** '새 대화': 기존 문맥을 지웁니다. */
    @PostMapping("/reset")
    public Map<String, String> reset(@AuthenticationPrincipal AuthUser user) {
        service.reset(user.userId());
        return Map.of("message", "새 대화를 시작합니다.");
    }
}
