package com.kfincheck.news;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class NewsService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(NewsService.class);
    private static final Map<String, String> COMPANIES = Map.of(
            "005930", "삼성전자", "000660", "SK하이닉스", "035420", "네이버",
            "035720", "카카오", "005380", "현대자동차", "000270", "기아",
            "051910", "LG화학", "006400", "삼성SDI");
    private final NaverNewsConfig config;
    private final RestClient client;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    private final Map<String, NewsFeed> cache = new HashMap<>();

    public NewsService(NaverNewsConfig config, RestClient.Builder builder) {
        this.config = config;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        client = builder.baseUrl("https://naverapihub.apigw.ntruss.com")
                .requestFactory(factory).build();
    }

    public synchronized NewsFeed latest(String stockCode) {
        String company = COMPANIES.get(stockCode);
        if (company == null) throw new NewsException(HttpStatus.NOT_FOUND,
                "UNSUPPORTED_STOCK", "해당 종목의 뉴스 검색은 아직 지원하지 않습니다.");
        NewsFeed existing = cache.get(stockCode);
        if (existing != null && existing.fetchedAt().plusSeconds(300).isAfter(Instant.now())) return existing;
        if (missing(config.getClientId()) || missing(config.getClientSecret())) {
            throw new NewsException(HttpStatus.SERVICE_UNAVAILABLE, "NEWS_KEY_NOT_CONFIGURED",
                    "네이버 뉴스 인증 설정이 필요합니다.");
        }
        JsonNode body;
        try {
            String response = client.get().uri(uri -> uri.path("/search/v1/news")
                    .queryParam("query", company).queryParam("display", 10)
                    .queryParam("start", 1).queryParam("sort", "date").queryParam("format", "json").build())
                    .header("X-NCP-APIGW-API-KEY-ID", config.getClientId())
                    .header("X-NCP-APIGW-API-KEY", config.getClientSecret())
                    .retrieve().body(String.class);
            // 제공처의 응답 Content-Type과 무관하게 JSON 구조를 명시적으로 검증한다.
            body = mapper.readTree(response);
        } catch (RestClientResponseException error) {
            String message = switch (error.getStatusCode().value()) {
                case 401, 403 -> "네이버 API HUB 인증키와 서비스 이용 설정을 확인해 주세요.";
                case 429 -> "뉴스 조회 한도에 도달했습니다. 잠시 후 다시 시도해 주세요.";
                default -> "뉴스 서버에서 자료를 가져오지 못했습니다. 잠시 후 다시 시도해 주세요.";
            };
            throw new NewsException(HttpStatus.BAD_GATEWAY, "NEWS_PROVIDER_ERROR", message);
        } catch (Exception error) {
            // 외부 원본 예외·응답·인증 헤더는 출력하지 않는다.
            LOG.warn("뉴스 통신 처리 실패: {} / {}", error.getClass().getSimpleName(),
                    error.getCause() == null ? "원인 없음" : error.getCause().getClass().getSimpleName());
            throw new NewsException(HttpStatus.BAD_GATEWAY, "NEWS_UNAVAILABLE",
                    "뉴스 서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
        if (body == null || !body.path("items").isArray()) {
            throw new NewsException(HttpStatus.BAD_GATEWAY, "NEWS_INVALID_RESPONSE", "뉴스 응답 형식을 확인할 수 없습니다.");
        }
        NewsFeed result = normalize(stockCode, company, body.path("items"));
        cache.put(stockCode, result);
        return result;
    }

    static NewsFeed normalize(String stockCode, String company, JsonNode items) {
        List<Article> articles = new ArrayList<>();
        Set<String> links = new HashSet<>();
        for (JsonNode item : items) {
            String link = safeLink(item.path("originallink").asText());
            if (link == null) link = safeLink(item.path("link").asText());
            String title = plainText(item.path("title").asText());
            if (link == null || title.isBlank() || !links.add(link)) continue;
            Instant publishedAt = null;
            try {
                publishedAt = ZonedDateTime.parse(item.path("pubDate").asText(),
                        DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            } catch (RuntimeException ignored) { /* 날짜가 없으면 시각을 임의로 생성하지 않는다. */ }
            articles.add(new Article(title, plainText(item.path("description").asText()), link, publishedAt));
            if (articles.size() >= 10) break;
        }
        return new NewsFeed(stockCode, company, "NAVER API HUB", Instant.now(), List.copyOf(articles));
    }

    static String plainText(String value) {
        return HtmlUtils.htmlUnescape(value.replaceAll("<[^>]*>", "")).replaceAll("\\s+", " ").trim();
    }

    static String safeLink(String value) {
        try {
            URI uri = URI.create(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null ? uri.toASCIIString() : null;
        } catch (RuntimeException ignored) { return null; }
    }

    private static boolean missing(String value) {
        return value == null || value.isBlank() || value.startsWith("YOUR_");
    }

    public record Article(String title, String description, String link, Instant publishedAt) { }
    public record NewsFeed(String stockCode, String companyName, String source, Instant fetchedAt,
            List<Article> articles) { }

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
