package com.kfincheck.chat;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChatService {
    private final AiServerClient client;
    private final ChatHistory history;
    private final ArticleSummaryChat articleSummary;

    public ChatService(AiServerClient client, ChatHistory history, ArticleSummaryChat articleSummary) {
        this.client = client;
        this.history = history;
        this.articleSummary = articleSummary;
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
        // 특정 기사 요약 요청은 여기서 처리한다(요약문은 대화 기록에 넣지 않고 news_summary에 24시간만 보관).
        var summary = articleSummary.handle(userId, conversationId, room.symbol(), message);
        if (summary.isPresent()) {
            history.appendPair(conversationId, message, articleSummary.forStorage(summary.get()));
            return summary.get();
        }
        var response = client.ask(new ChatModels.AiRequest(userId, conversationId, message,
                new ChatModels.Stock(room.symbol(), room.name()), history.context(conversationId)));
        if (response == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 서버의 응답이 비어 있습니다.");
        history.appendPair(conversationId, message, response);
        return response;
    }

    /** 저장된 대화를 불러오며 기사 요약은 보관 기간이 남은 경우에만 다시 채운다. */
    public List<ChatHistory.Message> messages(long userId, String conversationId) {
        return history.messages(userId, conversationId).stream().map(articleSummary::restore).toList();
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
