package com.example.stock_api.news;

import java.net.URI;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.HtmlUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 종목별 관련 기사 (NAVER API HUB 뉴스 검색).
 * - 종목코드를 기업명으로 바꿔 검색하고, 최신순 최대 10개를 돌려줍니다.
 * - 같은 종목의 성공 결과는 5분간 메모리에서 재사용합니다. (호출 한도 보호)
 * - 키는 application-local.properties 의 naver-news.client-id / naver-news.client-secret 에만 둡니다.
 */
@Service
public class NewsService {

    private static final Logger LOG = LoggerFactory.getLogger(NewsService.class);
    private static final long CACHE_SECONDS = 300;

    // 종목코드 → 검색어 (기업명). 목록에 없는 종목은 지원하지 않습니다.
    private static final Map<String, String> COMPANIES = Map.ofEntries(
            Map.entry("005930", "삼성전자"), Map.entry("000660", "SK하이닉스"),
            Map.entry("035420", "네이버"), Map.entry("035720", "카카오"),
            Map.entry("005380", "현대자동차"), Map.entry("000270", "기아"),
            Map.entry("051910", "LG화학"), Map.entry("006400", "삼성SDI"),
            Map.entry("373220", "LG에너지솔루션"), Map.entry("207940", "삼성바이오로직스"),
            Map.entry("068270", "셀트리온"), Map.entry("005490", "POSCO홀딩스"),
            Map.entry("012330", "현대모비스"), Map.entry("105560", "KB금융"),
            Map.entry("055550", "신한지주"), Map.entry("066570", "LG전자"),
            Map.entry("017670", "SK텔레콤"), Map.entry("015760", "한국전력"),
            Map.entry("323410", "카카오뱅크"), Map.entry("259960", "크래프톤"),
            Map.entry("352820", "하이브"), Map.entry("247540", "에코프로비엠"),
            Map.entry("196170", "알테오젠"));

    private final String clientId;
    private final String clientSecret;
    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Instant at, List<Article> articles) {}

    /** 프론트(NewsFeed.jsx)가 쓰는 필드 이름: headline, summary, source, datetime(초), url */
    public record Article(String id, String headline, String summary, String source, Long datetime, String url) {}

    public static class NewsException extends RuntimeException {
        private final HttpStatus status;

        public NewsException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        public HttpStatus status() {
            return status;
        }
    }

    public NewsService(@Value("${naver-news.client-id:}") String clientId,
                       @Value("${naver-news.client-secret:}") String clientSecret) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        this.client = RestClient.builder()
                .baseUrl("https://naverapihub.apigw.ntruss.com")
                .requestFactory(factory)
                .build();
    }

    public synchronized List<Article> latest(String code) {
        String company = COMPANIES.get(code);
        if (company == null) {
            throw new NewsException(HttpStatus.NOT_FOUND, "이 종목의 뉴스 검색은 아직 지원하지 않습니다.");
        }
        Cached hit = cache.get(code);
        if (hit != null && hit.at().plusSeconds(CACHE_SECONDS).isAfter(Instant.now())) {
            return hit.articles();
        }
        if (missing(clientId) || missing(clientSecret)) {
            throw new NewsException(HttpStatus.SERVICE_UNAVAILABLE,
                    "네이버 뉴스 키가 설정되지 않았습니다. application-local.properties 를 확인하세요.");
        }

        JsonNode body;
        try {
            String response = client.get()
                    .uri(uri -> uri.path("/search/v1/news")
                            .queryParam("query", company)
                            .queryParam("display", 50)
                            .queryParam("start", 1)
                            .queryParam("sort", "date")
                            .queryParam("format", "json")
                            .build())
                    .header("X-NCP-APIGW-API-KEY-ID", clientId)
                    .header("X-NCP-APIGW-API-KEY", clientSecret)
                    .retrieve()
                    .body(String.class);
            // 제공처의 Content-Type 과 상관없이 문자열로 받아 JSON 으로 해석합니다.
            body = mapper.readTree(response);
        } catch (RestClientResponseException e) {
            String message = switch (e.getStatusCode().value()) {
                case 401, 403 -> "네이버 API HUB 인증키와 서비스 이용 설정을 확인해 주세요.";
                case 429 -> "뉴스 조회 한도에 도달했습니다. 잠시 후 다시 시도해 주세요.";
                default -> "뉴스 서버에서 자료를 가져오지 못했습니다. 잠시 후 다시 시도해 주세요.";
            };
            LOG.warn("뉴스 조회 실패 HTTP {}", e.getStatusCode().value());
            throw new NewsException(HttpStatus.BAD_GATEWAY, message);
        } catch (Exception e) {
            // 키가 담긴 요청 헤더/원본 응답은 로그에 남기지 않습니다.
            LOG.warn("뉴스 통신 실패: {}", e.getClass().getSimpleName());
            throw new NewsException(HttpStatus.BAD_GATEWAY, "뉴스 서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }

        if (body == null || !body.path("items").isArray()) {
            throw new NewsException(HttpStatus.BAD_GATEWAY, "뉴스 응답 형식을 확인할 수 없습니다.");
        }
        List<Article> articles = normalize(body.path("items"), company);
        cache.put(code, new Cached(Instant.now(), articles));
        return articles;
    }

static List<Article> normalize(JsonNode items, String company) {
    List<Article> inTitle = new ArrayList<>();
    List<Article> inSummary = new ArrayList<>();
    Set<String> links = new HashSet<>();
    String keyword = company.toLowerCase();
    for (JsonNode item : items) {
        String link = safeLink(item.path("originallink").asText(""));
        if (link == null) {
            link = safeLink(item.path("link").asText(""));
        }
        String title = plainText(item.path("title").asText(""));
        if (link == null || title.isBlank() || !links.add(link)) {
            continue;
        }
        String summary = plainText(item.path("description").asText(""));
        Long datetime = null;
        try {
            datetime = ZonedDateTime.parse(item.path("pubDate").asText(""),
                    DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().getEpochSecond();
        } catch (RuntimeException ignored) {
            // 날짜가 없으면 시각을 임의로 만들지 않습니다.
        }
        Article article = new Article(link, title, summary, hostOf(link), datetime, link);
        if (title.toLowerCase().contains(keyword)) {
            inTitle.add(article);
        } else if (summary.toLowerCase().contains(keyword)) {
            inSummary.add(article);
        }
    }
        // 제목에 종목명이 있는 기사를 먼저 뽑고, 모자라면 요약에 있는 기사로 10개까지 채운 뒤 최신순으로 정렬합니다.
        List<Article> picked = new ArrayList<>(inTitle);
        picked.addAll(inSummary);
        if (picked.size() > 10) {
            picked = new ArrayList<>(picked.subList(0, 10));
        }
        picked.sort(java.util.Comparator
                .comparing((Article a) -> a.datetime() == null ? 0L : a.datetime())
                .reversed());
        return picked;

        }

    static String plainText(String value) {
        return HtmlUtils.htmlUnescape(value.replaceAll("<[^>]*>", "")).replaceAll("\\s+", " ").trim();
    }

    /** http/https 링크만 허용 (javascript: 등 차단) */
    static String safeLink(String value) {
        try {
            URI uri = URI.create(value);
            boolean web = "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme());
            return web && uri.getHost() != null && uri.getUserInfo() == null ? uri.toASCIIString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String hostOf(String link) {
        try {
            String host = URI.create(link).getHost();
            return host == null ? "" : host.replaceFirst("^www\\.", "");
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static boolean missing(String value) {
        return value == null || value.isBlank() || value.startsWith("YOUR_");
    }
}
