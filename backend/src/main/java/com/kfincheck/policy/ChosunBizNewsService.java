package com.kfincheck.policy;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class ChosunBizNewsService {
    private static final Logger log = LoggerFactory.getLogger(ChosunBizNewsService.class);
    private static final String HOST = "biz.chosun.com";
    private static final Duration MAX_AGE = Duration.ofHours(24);
    private static final Set<String> BROAD_KEYWORDS = Set.of("AI", "인공지능", "데이터", "콘텐츠");
    private static final List<String> SECTIONS = List.of("stock", "industry", "it-science");
    private final RestClient client;
    private volatile Feed feed;

    public ChosunBizNewsService(RestClient.Builder builder) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        this.client = builder.baseUrl("https://" + HOST).requestFactory(factory)
                .defaultHeader("User-Agent", "K-FinCheck/1.0").build();
    }

    @Scheduled(initialDelayString = "${news.chosunbiz.initial-delay-ms:10000}", fixedDelay = 1800000)
    public void refresh() {
        try {
            var items = new ArrayList<RssItem>();
            for (String section : SECTIONS) {
                String xml = client.get().uri("/arc/outboundfeeds/rss/category/{section}/?outputType=xml", section)
                        .retrieve().body(String.class);
                items.addAll(parse(xml));
            }
            Set<String> links = new HashSet<>();
            Instant now = Instant.now();
            feed = new Feed(now, items.stream()
                    .filter(item -> !item.publishedAt().isBefore(now.minus(MAX_AGE)))
                    .sorted(Comparator.comparing(RssItem::publishedAt).reversed())
                    .filter(item -> links.add(item.link()))
                    .toList());
        } catch (RuntimeException error) {
            if (feed != null && feed.fetchedAt().isBefore(Instant.now().minus(MAX_AGE))) feed = null;
            log.warn("조선비즈 RSS 수집 실패: {}", error.getClass().getSimpleName());
        }
    }

    public NewsList latest(String stockCode) {
        var profile = IndustryPolicyService.profiles().get(stockCode);
        if (profile == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "지원하지 않는 종목입니다.");
        Feed snapshot = feed;
        Instant cutoff = Instant.now().minus(MAX_AGE);
        if (snapshot == null || snapshot.fetchedAt().isBefore(cutoff)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "뉴스 목록을 준비 중입니다.");
        }
        var articles = snapshot.items().stream()
                .filter(item -> !item.publishedAt().isBefore(cutoff))
                .map(item -> new NewsArticle(item.title(), item.link(), item.publishedAt(), relation(profile, item.title())))
                .filter(item -> item.relation() != null)
                .limit(10)
                .toList();
        return new NewsList("조선비즈", snapshot.fetchedAt(), articles);
    }

    static List<RssItem> parse(String xml) {
        if (xml == null) throw new IllegalArgumentException("조선비즈 RSS 응답 없음");
        Element channel = Jsoup.parse(xml, "", Parser.xmlParser()).selectFirst("rss > channel");
        if (channel == null) throw new IllegalArgumentException("조선비즈 RSS 형식 오류");
        Set<String> links = new HashSet<>();
        return channel.select("> item").stream()
                .map(item -> new RssItem(item.select("> title").text(),
                        item.select("> link").text(), publishedAt(item.select("> pubDate").text())))
                .filter(item -> !item.title().isBlank() && item.publishedAt() != null && isArticleLink(item.link()))
                .sorted(Comparator.comparing(RssItem::publishedAt).reversed())
                .filter(item -> links.add(item.link()))
                .limit(100)
                .toList();
    }

    static String relation(IndustryPolicyService.Profile profile, String title) {
        String normalized = title.toLowerCase(Locale.ROOT);
        if (profile.aliases().stream().anyMatch(alias -> IndustryPolicyService.containsTerm(normalized, alias))) {
            return "DIRECT";
        }
        return profile.keywords().stream().filter(keyword -> !BROAD_KEYWORDS.contains(keyword))
                .anyMatch(keyword -> IndustryPolicyService.containsTerm(normalized, keyword)) ? "INDUSTRY" : null;
    }

    private static boolean isArticleLink(String link) {
        try {
            URI uri = URI.create(link);
            return "https".equals(uri.getScheme()) && HOST.equals(uri.getHost())
                    && uri.getUserInfo() == null && uri.getPort() == -1 && uri.getQuery() == null && uri.getFragment() == null
                    && uri.getPath().matches("/[a-z0-9-]+/[a-z0-9_-]+/\\d{4}/\\d{2}/\\d{2}/[A-Z0-9]+/");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static Instant publishedAt(String value) {
        try { return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); }
        catch (DateTimeParseException ignored) { return null; }
    }

    record RssItem(String title, String link, Instant publishedAt) { }
    private record Feed(Instant fetchedAt, List<RssItem> items) { }
    public record NewsList(String source, Instant fetchedAt, List<NewsArticle> articles) { }
    public record NewsArticle(String title, String link, Instant publishedAt, String relation) { }
}
