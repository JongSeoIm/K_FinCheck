package com.kfincheck.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kfincheck.news.NewsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ArticleSummaryChatTest {
    private static final String A1 = "https://biz.chosun.com/stock/2026/11/13/A1/";
    private static final String A2 = "https://biz.chosun.com/stock/2026/11/13/A2/";
    private final NewsService news = mock(NewsService.class);
    private final ChatHistory history = mock(ChatHistory.class);
    private final ObjectMapper json = new ObjectMapper();
    private final ArticleSummaryChat chat = new ArticleSummaryChat(news, history, json);

    private static NewsService.Article article(String title, String link) {
        return new NewsService.Article(title, "", link, null, "조선비즈", "direct", "직접 관련");
    }

    private static NewsService.Summary summary(String link) {
        return new NewsService.Summary(link, "삼성전자 HBM 공급", "조선비즈", "출처: 조선비즈", Instant.parse("2026-11-13T01:00:00Z"),
                "삼성전자가 HBM을 공급한다는 내용입니다.", "openai", Instant.parse("2026-11-13T02:00:00Z"), "AI 요약입니다.",
                false, Instant.parse("2026-11-14T02:00:00Z"));
    }

    @BeforeEach
    void setUp() {
        when(news.feed("005930")).thenReturn(new NewsService.NewsFeed("005930", "삼성전자", "조선비즈", "제목 키워드 기준", 5,
                true, "", null, List.of(article("삼성전자 HBM 공급", A1), article("삼성전자 노조 협상", A2)), Instant.now()));
        when(news.summarize(eq("005930"), anyString(), eq("user-7"))).thenAnswer(call -> summary(call.getArgument(1)));
    }

    @Test
    void ordinaryQuestionsGoToAiServer() {
        assertThat(chat.handle(7, "room-0001", "005930", "삼성전자 뉴스 요약해줘")).isEmpty();
        assertThat(chat.handle(7, "room-0001", "005930", "삼성전자 현재가 알려줘")).isEmpty();
        verifyNoInteractions(news);
    }

    @Test
    void summarizesByLinkNumberOrQuotedTitle() {
        var byLink = chat.handle(7, "room-0001", "005930", A1 + " 이 기사 요약해줘").orElseThrow();
        assertThat(byLink.presentation().path("type").asText()).isEqualTo("news_summary");
        assertThat(byLink.presentation().path("data").path("summary").asText()).contains("HBM");
        assertThat(byLink.presentation().path("data").path("link").asText()).isEqualTo(A1);

        when(history.latestNewsLinks("room-0001")).thenReturn(List.of(A1, A2));
        var byNumber = chat.handle(7, "room-0001", "005930", "2번 기사 요약해줘").orElseThrow();
        assertThat(byNumber.presentation().path("data").path("link").asText()).isEqualTo(A2);
        var byOrdinal = chat.handle(7, "room-0001", "005930", "첫 번째 기사 요약해 줘").orElseThrow();
        assertThat(byOrdinal.presentation().path("data").path("link").asText()).isEqualTo(A1);
        assertThat(chat.handle(7, "room-0001", "005930", "5번 기사 요약해줘").orElseThrow().answer()).contains("1~2번");

        var byTitle = chat.handle(7, "room-0001", "005930", "'노조 협상' 기사 요약해줘").orElseThrow();
        assertThat(byTitle.presentation().path("data").path("link").asText()).isEqualTo(A2);
        var many = chat.handle(7, "room-0001", "005930", "\"삼성전자\" 기사 요약해줘").orElseThrow();
        assertThat(many.presentation().path("type").asText()).isEqualTo("news");
        assertThat(many.presentation().path("data").path("articles")).hasSize(2);
    }

    @Test
    void numberWithoutListAsksForListFirst() {
        when(history.latestNewsLinks("room-0002")).thenReturn(List.of());
        assertThat(chat.handle(7, "room-0002", "005930", "1번 기사 요약해줘").orElseThrow().answer()).contains("기사 목록이 없습니다");
        verify(news, never()).summarize(any(), any(), any());
    }

    @Test
    void errorsBecomeChatAnswers() {
        when(news.summarize("005930", A1, "user-7")).thenThrow(new NewsService.NewsException(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "DAILY_LIMIT", "AI 요약은 하루 20건까지 요청할 수 있습니다."));
        var response = chat.handle(7, "room-0001", "005930", A1 + " 요약").orElseThrow();
        assertThat(response.answer()).contains("하루 20건");
        assertThat(response.presentation()).isNull();
    }

    @Test
    void historyKeepsOnlyTitleAndLinkAndRestoresWithinRetention() {
        var response = chat.handle(7, "room-0001", "005930", A1 + " 요약해줘").orElseThrow();
        var stored = chat.forStorage(response);
        assertThat(stored.presentation().path("data").has("summary")).isFalse();
        assertThat(stored.presentation().path("data").path("link").asText()).isEqualTo(A1);
        assertThat(stored.answer()).doesNotContain("HBM을 공급한다는");

        var message = new ChatHistory.Message("assistant", stored.answer(), stored);
        when(news.storedSummary(A1)).thenReturn(Optional.of(summary(A1)));
        assertThat(chat.restore(message).response().presentation().path("data").path("summary").asText()).contains("HBM");
        when(news.storedSummary(A1)).thenReturn(Optional.empty());
        var expired = chat.restore(message).response().presentation().path("data");
        assertThat(expired.path("expired").asBoolean()).isTrue();
        assertThat(expired.has("summary")).isFalse();
    }
}
