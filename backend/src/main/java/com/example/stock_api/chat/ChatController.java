package com.example.stock_api.chat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.stock_api.auth.LoginUser;
import com.example.stock_api.common.ApiException;
import com.example.stock_api.stock.Stock;
import com.example.stock_api.stock.StockService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

/** 화면의 채팅 패널이 쓰는 API. 로그인 확인은 SecurityConfig 에서 합니다. */
@Validated
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AiServerClient ai;
    private final ChatHistory history;
    private final StockService stocks;

    public ChatController(AiServerClient ai, ChatHistory history, StockService stocks) {
        this.ai = ai;
        this.history = history;
        this.stocks = stocks;
    }

    @GetMapping("/conversations")
    public List<ChatConversation> conversations(@AuthenticationPrincipal LoginUser user,
                                                @RequestParam @Pattern(regexp = "\\d{6}") String symbol) {
        return history.list(user.userId(), supported(symbol).getCode());
    }

    @PostMapping("/conversations")
    public ChatConversation create(@AuthenticationPrincipal LoginUser user,
                                   @Valid @RequestBody ChatRequests.NewConversation body) {
        return history.create(user.userId(), supported(body.symbol()));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public List<Map<String, Object>> messages(@AuthenticationPrincipal LoginUser user,
                                              @PathVariable @Pattern(regexp = ChatRequests.CONVERSATION_ID) String conversationId) {
        return history.messages(user.userId(), conversationId);
    }

    @DeleteMapping("/conversations/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal LoginUser user,
                       @PathVariable @Pattern(regexp = ChatRequests.CONVERSATION_ID) String conversationId) {
        history.remove(user.userId(), conversationId);
    }

    @PostMapping
    public Map<String, Object> ask(@AuthenticationPrincipal LoginUser user, @Valid @RequestBody ChatRequests.Ask ask) {
        ChatConversation room = history.owned(user.userId(), ask.conversationId());
        if (!room.getStockCode().equals(ask.stock().symbol())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CHAT_STOCK_MISMATCH", "대화의 종목이 일치하지 않습니다.");
        }
        supported(room.getStockCode());
        Map<String, Object> reply = ai.ask(user.userId(), ask, history.recent(room.getConversationId()),
                history.lastContext(room.getConversationId()));
        // 답변을 만들지 못한 질문은 저장하지 않고 화면에서 다시 시도합니다
        if (!"failed".equals(reply.get("status"))) {
            history.save(room.getConversationId(), ask.message(), reply);
        }
        Map<String, Object> result = new LinkedHashMap<>(reply);
        result.remove("context"); // 서버 사이에서만 쓰는 값
        return result;
    }

    private Stock supported(String symbol) {
        return stocks.find(symbol).orElseThrow(() -> ApiException.notSupported(symbol));
    }
}
