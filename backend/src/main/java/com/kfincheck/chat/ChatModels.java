package com.kfincheck.chat;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public final class ChatModels {
    private ChatModels() { }

    public record WebRequest(String conversationId, String message, Stock stock) { }
    public record Stock(String symbol, String name) { }
    public record ContextMessage(String role, String content) { }

    public record AiRequest(
            @JsonProperty("user_id") long userId,
            @JsonProperty("conversation_id") String conversationId,
            String message,
            Stock stock, List<ContextMessage> history) { }

    /**
     * basis: 답변 자료 기준일 (예: "2026-11-06(금) 종가 기준"). 화면에 표시한다.
     * usage: OpenAI 토큰 사용량. ChatUsageGuard 가 저장하고, 화면·대화 기록에는 보내지 않는다(withoutUsage).
     */
    public record AiResponse(String status, String answer, List<Source> sources, JsonNode presentation,
            @JsonAlias("context_message") String contextMessage, String basis, JsonNode usage) {
        public AiResponse(String status, String answer, List<Source> sources) {
            this(status, answer, sources, null, null, null, null);
        }
        public AiResponse(String status, String answer, List<Source> sources, JsonNode presentation, String contextMessage) {
            this(status, answer, sources, presentation, contextMessage, null, null);
        }
        public AiResponse {
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
        public AiResponse withoutUsage() {
            return usage == null ? this : new AiResponse(status, answer, sources, presentation, contextMessage, basis, null);
        }
    }

    public record Source(
            String provider,
            String label,
            @JsonAlias("basis_at") String basisAt,
            String url) { }
}
