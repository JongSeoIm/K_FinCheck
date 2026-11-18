package com.kfincheck.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kfincheck.news.NewsService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 대화에서 특정 기사 1건의 요약을 요청하면 처리한다.
 * 기사 지정: 기사 링크 / 이 대화의 뉴스 목록 번호("2번 기사 요약해줘") / 따옴표 안 제목 일부.
 * 원문은 AI 서버가 요청 시 1회만 가져와 요약 후 폐기하고, 요약문은 news_summary 테이블에 24시간만 보관한다.
 * 대화 기록(chat_message)에는 요약문을 넣지 않고 기사 제목·링크만 남긴다.
 */
@Component
public class ArticleSummaryChat {
    static final String TYPE = "news_summary";
    private static final Pattern LINK = Pattern.compile("https://[^\\s<>\"'()]+");
    private static final Pattern NUMBER = Pattern.compile("(\\d{1,2})\\s*번(?:째)?");
    private static final Pattern ORDINAL = Pattern.compile("(첫|두|세|네|다섯|여섯|일곱|여덟|아홉|열)\\s*(?:번째|째)");
    private static final Pattern QUOTED = Pattern.compile("['\"‘“「『]([^'\"’”」』]{2,100})['\"’”」』]");
    private static final Map<String, Integer> ORDINALS = Map.of("첫", 1, "두", 2, "세", 3, "네", 4, "다섯", 5,
            "여섯", 6, "일곱", 7, "여덟", 8, "아홉", 9, "열", 10);
    private static final String HOW_TO = "요약할 기사를 정해 주세요. 기사 링크를 붙여 넣거나, 먼저 '관련 기사 알려줘'로 목록을 받은 뒤 "
            + "'1번 기사 요약해줘'처럼 번호로, 또는 '\"제목 일부\" 기사 요약해줘'처럼 따옴표로 제목을 적어 주세요.";

    private final NewsService news;
    private final ChatHistory history;
    private final ObjectMapper json;

    public ArticleSummaryChat(NewsService news, ChatHistory history, ObjectMapper json) {
        this.news = news;
        this.history = history;
        this.json = json;
    }

    /** 기사 요약 요청이면 답변을 만들고, 아니면 비어 있는 값을 돌려 기존 AI 대화로 넘긴다. */
    public Optional<ChatModels.AiResponse> handle(long userId, String conversationId, String symbol, String message) {
        if (!message.contains("요약")) return Optional.empty();
        Matcher link = LINK.matcher(message);
        Integer number = number(message);
        Matcher quoted = QUOTED.matcher(message);
        boolean article = message.contains("기사") || message.contains("뉴스");
        if (!link.find() && !(number != null && article) && !(quoted.find() && article)) return Optional.empty();

        NewsService.NewsFeed feed;
        try {
            feed = news.feed(symbol);
        } catch (NewsService.NewsException error) {
            return answer(error.getMessage(), message);
        }
        String target;
        if (link.reset().find()) {
            target = link.group().replaceAll("[.,。]+$", "");
        } else if (number != null) {
            List<String> listed = history.latestNewsLinks(conversationId);
            if (listed.isEmpty()) return answer("이 대화에서 받은 기사 목록이 없습니다. 먼저 '관련 기사 알려줘'로 목록을 받은 뒤 번호로 요청해 주세요.", message);
            if (number < 1 || number > listed.size()) return answer("목록에 " + number + "번 기사가 없습니다. 1~" + listed.size() + "번 중에서 골라 주세요.", message);
            target = listed.get(number - 1);
        } else {
            quoted.reset().find();
            String phrase = compact(quoted.group(1));
            List<NewsService.Article> found = feed.articles().stream().filter(item -> compact(item.title()).contains(phrase)).toList();
            if (found.isEmpty()) return answer("현재 기사 목록에서 제목에 '" + quoted.group(1).trim() + "'이(가) 들어간 기사를 찾지 못했습니다. " + HOW_TO, message);
            if (found.size() > 1) return Optional.of(candidates(feed, found.subList(0, Math.min(5, found.size())), message));
            target = found.getFirst().link();
        }
        try {
            NewsService.Summary summary = news.summarize(symbol, target, "user-" + userId);
            return Optional.of(response(summary, message));
        } catch (NewsService.NewsException error) {
            return answer(error.getMessage(), message);
        }
    }

    /** 대화 기록에 저장할 형태: 요약문을 빼고 기사 제목·링크·출처만 남긴다. */
    public ChatModels.AiResponse forStorage(ChatModels.AiResponse response) {
        JsonNode presentation = response.presentation();
        if (presentation == null || !TYPE.equals(presentation.path("type").asText())) return response;
        ObjectNode data = ((ObjectNode) presentation.path("data")).deepCopy();
        data.remove(List.of("summary", "notice", "mode", "generatedAt", "cached"));
        ObjectNode stored = json.createObjectNode().put("type", TYPE);
        stored.set("data", data);
        return new ChatModels.AiResponse(response.status(), response.answer(), response.sources(), stored, response.contextMessage());
    }

    /** 대화 기록을 다시 열 때: 보관 기간(24시간)이 남은 요약문만 다시 채우고, 지났으면 만료로 표시한다. */
    public ChatHistory.Message restore(ChatHistory.Message message) {
        ChatModels.AiResponse response = message.response();
        if (response == null || response.presentation() == null || !TYPE.equals(response.presentation().path("type").asText())) return message;
        String link = response.presentation().path("data").path("link").asText("");
        Optional<NewsService.Summary> stored = link.isEmpty() ? Optional.empty() : news.storedSummary(link);
        JsonNode presentation;
        if (stored.isPresent()) {
            presentation = presentation(stored.get());
        } else {
            ObjectNode copy = response.presentation().deepCopy();
            ((ObjectNode) copy.path("data")).put("expired", true);
            presentation = copy;
        }
        return new ChatHistory.Message(message.role(), message.text(), new ChatModels.AiResponse(
                response.status(), response.answer(), response.sources(), presentation, response.contextMessage()));
    }

    private ChatModels.AiResponse response(NewsService.Summary summary, String message) {
        var source = new ChatModels.Source("RSS", "조선비즈 기사 원문 1건 · 요청 시 1회 AI 요약 · 요약문 24시간 보관",
                summary.generatedAt() == null ? null : summary.generatedAt().toString(), summary.link());
        return new ChatModels.AiResponse("answered", "'" + summary.title() + "' 기사를 AI로 요약했습니다.",
                List.of(source), presentation(summary), message);
    }

    private JsonNode presentation(NewsService.Summary summary) {
        ObjectNode data = json.createObjectNode()
                .put("link", summary.link()).put("title", summary.title()).put("sourceName", summary.sourceName())
                .put("attribution", summary.attribution()).put("summary", summary.summary()).put("mode", summary.mode())
                .put("notice", summary.notice()).put("cached", summary.cached())
                .put("publishedAt", summary.publishedAt() == null ? null : summary.publishedAt().toString())
                .put("generatedAt", summary.generatedAt() == null ? null : summary.generatedAt().toString())
                .put("expiresAt", summary.expiresAt() == null ? null : summary.expiresAt().toString());
        ObjectNode presentation = json.createObjectNode().put("type", TYPE);
        presentation.set("data", data);
        return presentation;
    }

    /** 제목이 여러 기사와 맞으면 번호가 붙은 목록으로 보여 주고 번호로 다시 고르게 한다. */
    private ChatModels.AiResponse candidates(NewsService.NewsFeed feed, List<NewsService.Article> found, String message) {
        ObjectNode data = json.createObjectNode().put("tool", "get_company_news").put("name", feed.companyName())
                .put("symbol", feed.stockCode()).put("basis", "조선비즈 RSS 제목 키워드 기준");
        ArrayNode articles = data.putArray("articles");
        for (var item : found) {
            articles.addObject().put("title", item.title()).put("link", item.link())
                    .put("publishedAt", item.publishedAt() == null ? null : item.publishedAt().toString());
        }
        ObjectNode presentation = json.createObjectNode().put("type", "news");
        presentation.set("data", data);
        return new ChatModels.AiResponse("answered", "제목이 맞는 기사가 " + found.size()
                + "건 있습니다. 아래 목록에서 '1번 기사 요약해줘'처럼 번호로 골라 주세요.", List.of(), presentation, message);
    }

    private static Optional<ChatModels.AiResponse> answer(String text, String message) {
        return Optional.of(new ChatModels.AiResponse("answered", text, List.of(), null, message));
    }

    static Integer number(String message) {
        Matcher digits = NUMBER.matcher(message);
        if (digits.find()) return Integer.parseInt(digits.group(1));
        Matcher ordinal = ORDINAL.matcher(message);
        return ordinal.find() ? ORDINALS.get(ordinal.group(1)) : null;
    }

    private static String compact(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase();
    }
}
