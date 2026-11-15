package com.kfincheck.news;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 8단계. AI 서버의 조선비즈 RSS 목록(제목 기준 관련도 구분 포함)을 화면에 전달하고,
 * AI 대화에서 요청한 기사 1건의 일회성 요약을 만든다(요약문만 DB에 24시간 보관).
 */
@Service
public class NewsService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(NewsService.class);
    private static final Set<String> SUPPORTED = Set.of(
            "005930", "000660", "035420", "035720", "005380", "000270", "051910", "006400");
    private static final Set<String> RELEVANCE = Set.of("direct", "industry", "none");
    private static final long CACHE_SECONDS = 60;
    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, NewsFeed> cache = new ConcurrentHashMap<>();
    private final NewsSummaryStore summaries;

    public NewsService(@Qualifier("aiServerRestClient") RestClient client, NewsSummaryStore summaries) {
        this.client = client;
        this.summaries = summaries;
    }

    public NewsFeed feed(String stockCode) {
        if (!SUPPORTED.contains(stockCode)) throw new NewsException(HttpStatus.NOT_FOUND,
                "UNSUPPORTED_STOCK", "해당 종목의 뉴스는 아직 지원하지 않습니다.");
        NewsFeed cached = cache.get(stockCode);
        if (cached != null && cached.cachedAt().plusSeconds(CACHE_SECONDS).isAfter(Instant.now())) return cached;
        NewsFeed result = normalize(stockCode, call(() -> client.get()
                .uri("/api/news/feed?stock={code}", stockCode).retrieve().body(String.class)));
        cache.put(stockCode, result);
        return result;
    }

    /**
     * 목록에 있는 기사 1건을 요약한다. 24시간 안에 만든 요약문이 DB에 있으면 그대로 쓰고,
     * 없으면 AI 서버에 요청(원문 1회 요청·요약 후 폐기)한 뒤 요약문만 DB에 24시간 보관한다.
     */
    public Summary summarize(String stockCode, String link, String userKey) {
        Article article = feed(stockCode).articles().stream().filter(item -> item.link().equals(link)).findFirst()
                .orElseThrow(() -> new NewsException(HttpStatus.NOT_FOUND, "ARTICLE_NOT_FOUND",
                        "현재 기사 목록에 없는 기사입니다. 뉴스 목록을 새로 확인해 주세요."));
        var stored = summaries.find(link, Instant.now());
        if (stored.isPresent()) return stored.get();
        JsonNode body = call(() -> client.post().uri("/api/news/summary")
                .body(Map.of("link", link, "userKey", userKey)).retrieve().body(String.class));
        String summary = body.path("summary").asText("").trim();
        if (summary.isEmpty()) throw new NewsException(HttpStatus.BAD_GATEWAY, "SUMMARY_INVALID", "요약 응답 형식을 확인할 수 없습니다.");
        var created = new Summary(link, article.title(), body.path("sourceName").asText(article.sourceName()),
                body.path("attribution").asText("출처: 조선비즈"), instant(body.path("publishedAt").asText(null)),
                summary, body.path("mode").asText("unknown"), instant(body.path("generatedAt").asText(null)),
                body.path("notice").asText(""), false, null);
        return summaries.save(created, Instant.now());
    }

    /** 대화 기록을 다시 열 때 쓰는 조회: 보관 기간이 남은 요약문만 돌려준다. */
    public java.util.Optional<Summary> storedSummary(String link) {
        return summaries.find(link, Instant.now());
    }

    private JsonNode call(Supplier<String> request) {
        try {
            String response = request.get();
            JsonNode body = response == null ? null : mapper.readTree(response);
            if (body == null || !body.isObject()) throw invalid();
            return body;
        } catch (RestClientResponseException error) {
            throw fromAiServer(error);
        } catch (NewsException error) {
            throw error;
        } catch (RestClientException error) {
            LOG.warn("뉴스 서버 연결 실패: {}", error.getClass().getSimpleName());
            throw new NewsException(HttpStatus.SERVICE_UNAVAILABLE, "NEWS_UNAVAILABLE",
                    "뉴스 서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        } catch (Exception error) {
            throw invalid();
        }
    }

    private static NewsException invalid() {
        return new NewsException(HttpStatus.BAD_GATEWAY, "NEWS_INVALID_RESPONSE", "뉴스 응답 형식을 확인할 수 없습니다.");
    }

    private NewsException fromAiServer(RestClientResponseException error) {
        String code = "NEWS_PROVIDER_ERROR";
        String message = "뉴스 서버에서 자료를 가져오지 못했습니다.";
        try {
            JsonNode detail = mapper.readTree(error.getResponseBodyAsString()).path("detail");
            if (detail.path("code").isTextual()) code = detail.path("code").asText();
            if (detail.path("error").isTextual()) message = detail.path("error").asText();
        } catch (Exception ignored) { /* 형식이 다르면 기본 문구를 쓴다. */ }
        HttpStatus status = switch (error.getStatusCode().value()) {
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 422 -> HttpStatus.UNPROCESSABLE_ENTITY;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return new NewsException(status, code, message);
    }

    static NewsFeed normalize(String stockCode, JsonNode body) {
        if (!stockCode.equals(body.path("stockCode").asText())) throw invalid();
        List<Article> articles = new ArrayList<>();
        Set<String> links = new HashSet<>();
        for (JsonNode item : body.path("articles")) {
            String link = safeLink(item.path("link").asText());
            String title = item.path("title").asText("").replaceAll("\\s+", " ").trim();
            String relevance = item.path("relevance").asText("none");
            if (link == null || title.isEmpty() || !links.add(link)) continue;
            if (!RELEVANCE.contains(relevance)) relevance = "none";
            articles.add(new Article(title, "", link, instant(item.path("publishedAt").asText(null)),
                    item.path("sourceName").asText("조선비즈"), relevance, item.path("relevanceLabel").asText("")));
            if (articles.size() >= 60) break;
        }
        return new NewsFeed(stockCode, body.path("companyName").asText(stockCode), body.path("source").asText("조선비즈"),
                body.path("basis").asText("관련도는 본문 분석이 아닌 제목 키워드 기준입니다."),
                body.path("periodDays").asInt(5),
                body.path("summaryAvailable").asBoolean(false), body.path("summaryNotice").asText(""),
                instant(body.path("fetchedAt").asText(null)), List.copyOf(articles), Instant.now());
    }

    static Instant instant(String value) {
        if (value == null || value.isBlank()) return null;
        try { return OffsetDateTime.parse(value).toInstant(); }
        catch (RuntimeException ignored) { return null; }   // 시각이 없으면 임의로 만들지 않는다.
    }

    static String safeLink(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    ? uri.toASCIIString() : null;
        } catch (RuntimeException ignored) { return null; }
    }

    /** description은 챗봇 호환용 빈 값(기사 본문·설명은 전달하지 않는다). */
    public record Article(String title, String description, String link, Instant publishedAt, String sourceName,
            String relevance, String relevanceLabel) { }
    public record NewsFeed(String stockCode, String companyName, String source, String basis, int periodDays,
            boolean summaryAvailable, String summaryNotice, Instant fetchedAt,
            List<Article> articles, @JsonIgnore Instant cachedAt) { }
    public record Summary(String link, String title, String sourceName, String attribution, Instant publishedAt,
            String summary, String mode, Instant generatedAt, String notice, boolean cached, Instant expiresAt) {
        public Summary withExpiresAt(Instant value) {
            return new Summary(link, title, sourceName, attribution, publishedAt, summary, mode, generatedAt, notice, cached, value);
        }
    }

    public static class NewsException extends RuntimeException {
        private final HttpStatus status;
        private final String code;
        public NewsException(HttpStatus status, String code, String message) {
            super(message); this.status = status; this.code = code;
        }
        public HttpStatus status() { return status; }
        public String code() { return code; }
    }
}
