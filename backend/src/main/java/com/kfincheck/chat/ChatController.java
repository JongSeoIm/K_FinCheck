package com.kfincheck.chat;

import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import com.kfincheck.auth.AccountService;

@RestController
public class ChatController {
    private final ChatService service;
    private final ChatHistory history;

    public ChatController(ChatService service, ChatHistory history) {
        this.service = service;
        this.history = history;
    }
    @GetMapping("/api/chat/conversations")
    public java.util.List<ChatHistory.Room> list(Authentication auth, @RequestParam String symbol) {
        return history.list(user(auth).userId(), symbol);
    }
    @PostMapping("/api/chat/conversations")
    public ChatHistory.Room create(Authentication auth, @RequestBody ChatModels.Stock stock) {
        return history.create(user(auth).userId(), stock);
    }
    @GetMapping("/api/chat/conversations/{id}/messages")
    public java.util.List<ChatHistory.Message> messages(Authentication auth, @PathVariable String id) {
        return history.messages(user(auth).userId(), id);
    }
    @DeleteMapping("/api/chat/conversations/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(Authentication auth, @PathVariable String id) {
        history.remove(user(auth).userId(), id);
    }

    @PostMapping("/api/chat")
    public ChatModels.AiResponse chat(Authentication authentication, @RequestBody ChatModels.WebRequest request) {
        return service.ask(user(authentication).userId(), request);
    }

    private static AccountService.User user(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AccountService.User user))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        return user;
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> requestError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode())
                .body(Map.of("error", Objects.requireNonNullElse(error.getReason(), "요청을 처리하지 못했습니다.")));
    }

    @ExceptionHandler(RestClientException.class)
    ResponseEntity<Map<String, String>> aiServerError() {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "AI 서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요."));
    }
}
