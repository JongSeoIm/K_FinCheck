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

    public record AiResponse(String status, String answer, List<Source> sources, JsonNode presentation,
            @JsonAlias("context_message") String contextMessage) {
        public AiResponse(String status, String answer, List<Source> sources) {
            this(status, answer, sources, null, null);
        }
        public AiResponse {
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }

    public record Source(
            String provider,
            String label,
            @JsonAlias("basis_at") String basisAt,
            String url) { }
}
