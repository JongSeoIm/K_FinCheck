package com.kfincheck.chat;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChatService {
    private final AiServerClient client;
    private final ChatHistory history;
    private final ChatUsageGuard usage;

    @org.springframework.beans.factory.annotation.Autowired
    public ChatService(AiServerClient client, ChatHistory history, ChatUsageGuard usage) {
        this.client = client;
        this.history = history;
        this.usage = usage;
    }

    /** 호출 제한·사용량 기록 없이 만든다(단위 테스트용). */
    public ChatService(AiServerClient client, ChatHistory history) {
        this(client, history, ChatUsageGuard.unlimited());
    }

    @org.springframework.transaction.annotation.Transactional
    public ChatModels.AiResponse ask(long userId, ChatModels.WebRequest request) {
        if (request == null || request.stock() == null) throw bad("종목 정보가 필요합니다.");
        String conversationId = trim(request.conversationId());
        String message = trim(request.message());
        String symbol = trim(request.stock().symbol());
        String name = trim(request.stock().name());
        if (!conversationId.matches("[A-Za-z0-9_-]{8,100}")) throw bad("대화 식별자가 올바르지 않습니다.");
        if (message.isEmpty() || message.length() > 1500) throw bad("질문은 1~1500자로 입력해 주세요.");
        if (!symbol.matches("\\d{6}")) throw bad("종목 코드는 숫자 6자리여야 합니다.");
        if (name.isEmpty() || name.length() > 100) throw bad("기업명이 올바르지 않습니다.");
        var room = history.owned(userId, conversationId, true);
        if (!room.symbol().equals(symbol)) throw bad("대화의 종목이 일치하지 않습니다.");
        usage.check(userId);  // 분당·일별 호출, 일일 토큰 한도. 넘으면 429 이고 AI 서버를 부르지 않는다.
        var response = client.ask(new ChatModels.AiRequest(userId, conversationId, message,
                new ChatModels.Stock(room.symbol(), room.name()), history.context(conversationId)));
        if (response == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 서버의 응답이 비어 있습니다.");
        usage.record(userId, conversationId, response);
        var visible = response.withoutUsage();
        history.appendPair(conversationId, message, visible);
        return visible;
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
