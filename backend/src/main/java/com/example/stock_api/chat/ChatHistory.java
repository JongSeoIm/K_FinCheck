package com.example.stock_api.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.stock_api.common.ApiException;
import com.example.stock_api.stock.Stock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/** 대화 기록. DB 에는 삭제 전까지 전체를 보관하고, AI 에게는 최근 메시지 10개만 보냅니다. */
@Slf4j
@Service
public class ChatHistory {

    static final int CONTEXT_MESSAGES = 10;
    private static final int TITLE_LENGTH = 80;

    private final ChatMapper mapper;
    private final ObjectMapper json;

    public ChatHistory(ChatMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    public List<ChatConversation> list(long userId, String stockCode) {
        return mapper.findConversations(userId, stockCode);
    }

    public ChatConversation create(long userId, Stock stock) {
        String id = UUID.randomUUID().toString();
        mapper.insertConversation(id, userId, stock.getCode(), stock.getName());
        return owned(userId, id);
    }

    /** 내 대화가 아니면 있는지도 알려 주지 않도록 같은 404 로 답합니다 */
    public ChatConversation owned(long userId, String conversationId) {
        ChatConversation room = mapper.findConversation(userId, conversationId);
        if (room == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CHAT_NOT_FOUND", "대화를 찾을 수 없습니다.");
        }
        return room;
    }

    /** 화면 복원용: 내용과 출처 */
    public List<Map<String, Object>> messages(long userId, String conversationId) {
        owned(userId, conversationId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (ChatMessage message : mapper.findMessages(conversationId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", message.getRole());
            item.put("text", message.getContent());
            item.put("sources", read(message.getResponseJson()).getOrDefault("sources", List.of()));
            result.add(item);
        }
        return result;
    }

    /** AI 문맥: 최근 메시지 10개 */
    public List<Map<String, String>> recent(String conversationId) {
        return mapper.findRecentMessages(conversationId, CONTEXT_MESSAGES).stream()
                .map(message -> Map.of("role", message.getRole(), "content", message.getContextContent()))
                .toList();
    }

    /** 후속 질문용: 직전 답변의 질문 종류·기업·연도 */
    public Object lastContext(String conversationId) {
        return read(mapper.findLastResponse(conversationId)).get("context");
    }

    @Transactional
    public void save(String conversationId, String question, Map<String, Object> reply) {
        String answer = String.valueOf(reply.get("answer"));
        mapper.insertMessage(conversationId, "user", question, question, null);
        mapper.insertMessage(conversationId, "assistant", answer, answer, write(reply));
        mapper.touchConversation(conversationId, question.substring(0, Math.min(question.length(), TITLE_LENGTH)));
    }

    public void remove(long userId, String conversationId) {
        owned(userId, conversationId);
        mapper.deleteConversation(userId, conversationId);
    }

    private String write(Map<String, Object> reply) {
        try {
            return json.writeValueAsString(reply);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("대화를 저장하지 못했습니다.", e);
        }
    }

    private Map<String, Object> read(String response) {
        if (response == null) {
            return Map.of();
        }
        try {
            return json.readValue(response, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.warn("저장된 AI 응답을 읽지 못했습니다: {}", e.getOriginalMessage());
            return Map.of();
        }
    }
}
