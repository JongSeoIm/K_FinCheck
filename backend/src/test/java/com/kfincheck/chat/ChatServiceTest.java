package com.kfincheck.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class ChatServiceTest {
    @Test
    void preservesToolPresentationAndSourceFromAiResponse() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var response = mapper.readValue("""
                {"status":"answered","answer":"실적 안내","sources":[
                  {"provider":"OpenDART","label":"사업보고서","basis_at":"2025년"}],
                  "presentation":{"type":"financial","data":{"metrics":[{"current":"3336059.4"}]}}}
                """, ChatModels.AiResponse.class);
        assertThat(response.sources().getFirst().basisAt()).isEqualTo("2025년");
        assertThat(response.presentation().path("data").path("metrics").get(0).path("current").asText()).isEqualTo("3336059.4");
        assertThat(mapper.writeValueAsString(response)).contains("presentation", "3336059.4");
    }

    private final AiServerClient client = mock(AiServerClient.class);
    private final ChatHistory history = mock(ChatHistory.class);
    private final ChatService service = new ChatService(client, history);

    @Test
    void injectsAuthenticatedUserAndForwardsValidatedRequest() {
        var expected = new ChatModels.AiResponse("answered", "답변", List.of());
        when(client.ask(any())).thenReturn(expected);
        when(history.owned(7L, "user-7-stock-005930", true)).thenReturn(new ChatHistory.Room("user-7-stock-005930", "005930", "삼성전자", "새 대화", ""));
        when(history.context("user-7-stock-005930")).thenReturn(List.of());

        var result = service.ask(7L, new ChatModels.WebRequest(
                "user-7-stock-005930", " 삼성전자 현재가 알려줘 ",
                new ChatModels.Stock("005930", "삼성전자")));

        var request = ArgumentCaptor.forClass(ChatModels.AiRequest.class);
        verify(client).ask(request.capture());
        assertThat(result).isSameAs(expected);
        assertThat(request.getValue().userId()).isEqualTo(7L);
        assertThat(request.getValue().message()).isEqualTo("삼성전자 현재가 알려줘");
        assertThat(request.getValue().stock().symbol()).isEqualTo("005930");
        assertThat(request.getValue().history()).isEmpty();
        verify(history).appendPair("user-7-stock-005930", "삼성전자 현재가 알려줘", expected);
    }

    @Test
    void rejectsInvalidStockBeforeCallingAiServer() {
        assertThatThrownBy(() -> service.ask(7L, new ChatModels.WebRequest(
                "user-7-stock-invalid", "현재가 알려줘", new ChatModels.Stock("5930", "삼성전자"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("종목 코드는 숫자 6자리");
    }
}
