package com.example.stock_api.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.stock_api.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;

class ChatHistoryTest {

    private static final String ROOM = "11111111-1111-1111-1111-111111111111";

    private final ChatMapper mapper = mock(ChatMapper.class);
    private final ChatHistory history = new ChatHistory(mapper, new ObjectMapper());

    private static ChatMessage message(String role, String content, String context, String json) {
        ChatMessage item = new ChatMessage();
        item.setRole(role);
        item.setContent(content);
        item.setContextContent(context);
        item.setResponseJson(json);
        return item;
    }

    @Test
    void otherUsersConversationIsNotFound() {
        when(mapper.findConversation(2L, ROOM)).thenReturn(null);
        ApiException error = assertThrows(ApiException.class, () -> history.messages(2L, ROOM));
        assertEquals("CHAT_NOT_FOUND", error.getCode());
        verify(mapper, never()).findMessages(anyString());
    }

    @Test
    void otherUserCannotDeleteConversation() {
        when(mapper.findConversation(2L, ROOM)).thenReturn(null);
        assertThrows(ApiException.class, () -> history.remove(2L, ROOM));
        verify(mapper, never()).deleteConversation(2L, ROOM);
    }

    @Test
    void sendsOnlyRecentTenMessagesToAi() {
        when(mapper.findRecentMessages(ROOM, 10)).thenReturn(List.of(message("user", "질문", "질문", null)));
        assertEquals(List.of(Map.of("role", "user", "content", "질문")), history.recent(ROOM));
        verify(mapper).findRecentMessages(ROOM, ChatHistory.CONTEXT_MESSAGES);
    }

    @Test
    void savesQuestionAndAnswerWithLongTitleCut() {
        String question = "가".repeat(100);
        history.save(ROOM, question, Map.of("answer", "답변", "status", "answered"));
        verify(mapper).insertMessage(eq(ROOM), eq("user"), eq(question), eq(question), isNull());
        verify(mapper).insertMessage(eq(ROOM), eq("assistant"), eq("답변"), eq("답변"), anyString());
        verify(mapper).touchConversation(ROOM, "가".repeat(80));
    }

    @Test
    void restoresSourcesFromSavedResponse() {
        when(mapper.findConversation(1L, ROOM)).thenReturn(new ChatConversation());
        when(mapper.findMessages(ROOM)).thenReturn(List.of(
                message("user", "질문", null, null),
                message("assistant", "답변", null, "{\"sources\":[{\"provider\":\"KIS\"}]}")));
        List<Map<String, Object>> result = history.messages(1L, ROOM);
        assertEquals(List.of(), result.get(0).get("sources"));
        assertEquals(List.of(Map.of("provider", "KIS")), result.get(1).get("sources"));
    }

    @Test
    void lastContextComesFromLastAnswer() {
        when(mapper.findLastResponse(ROOM)).thenReturn("{\"context\":{\"company\":\"삼성전자\"}}");
        assertEquals(Map.of("company", "삼성전자"), history.lastContext(ROOM));
    }

    @Test
    void brokenSavedResponseDoesNotBreakRestore() {
        when(mapper.findLastResponse(ROOM)).thenReturn("not json");
        assertNull(history.lastContext(ROOM));
        verify(mapper, never()).insertMessage(any(), any(), any(), any(), any());
    }
}
