package com.example.stock_api.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.stock_api.auth.LoginUser;
import com.example.stock_api.common.ApiException;
import com.example.stock_api.stock.Stock;
import com.example.stock_api.stock.StockService;

class ChatControllerTest {

    private static final String ROOM = "11111111-1111-1111-1111-111111111111";
    private static final LoginUser USER = new LoginUser(1L, "tester", "테스터");

    private final AiServerClient ai = mock(AiServerClient.class);
    private final ChatHistory history = mock(ChatHistory.class);
    private final StockService stocks = mock(StockService.class);
    private final ChatController controller = new ChatController(ai, history, stocks);

    private static ChatRequests.Ask ask(String symbol) {
        return new ChatRequests.Ask(ROOM, "질문", new ChatRequests.Stock(symbol, "삼성전자"));
    }

    @BeforeEach
    void room() {
        ChatConversation room = new ChatConversation();
        room.setConversationId(ROOM);
        room.setStockCode("005930");
        when(history.owned(1L, ROOM)).thenReturn(room);
        when(history.recent(ROOM)).thenReturn(List.of());
        when(stocks.find("005930")).thenReturn(Optional.of(new Stock("005930", "삼성전자", "KOSPI", null, null)));
    }

    @Test
    void savesAnswerAndHidesInternalContext() {
        Map<String, Object> reply = Map.of("status", "answered", "answer", "답변", "context", Map.of());
        when(ai.ask(anyLong(), any(), any(), any())).thenReturn(reply);

        Map<String, Object> result = controller.ask(USER, ask("005930"));

        assertFalse(result.containsKey("context"));
        verify(history).save(ROOM, "질문", reply);
    }

    @Test
    void failedAnswerIsNotSaved() {
        when(ai.ask(anyLong(), any(), any(), any())).thenReturn(Map.of("status", "failed", "answer", "실패"));
        controller.ask(USER, ask("005930"));
        verify(history, never()).save(anyString(), anyString(), any());
    }

    @Test
    void differentStockIsRejectedBeforeCallingAi() {
        ApiException error = assertThrows(ApiException.class, () -> controller.ask(USER, ask("000660")));
        assertEquals("CHAT_STOCK_MISMATCH", error.getCode());
        verify(ai, never()).ask(anyLong(), any(), any(), any());
    }
}
