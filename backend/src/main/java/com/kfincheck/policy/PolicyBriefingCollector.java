package com.kfincheck.policy;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class PolicyBriefingCollector {
    static final String SOURCE = "KOREA_KR";
    private static final String HOST = "www.korea.kr";
    private static final String LIST_PATH = "/news/policyNewsList.do";
    private static final String DETAIL_PATH = "/news/policyNewsView.do";
    private static final int MAX_LIST_ITEMS = 10;
    private static final long DETAIL_DELAY_MILLIS = 1200;

    private final RestClient client;

    PolicyBriefingCollector(RestClient.Builder builder) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        client = builder.baseUrl("https://" + HOST).requestFactory(factory)
                .defaultHeader("User-Agent", "K-FinCheck/1.0 (educational project)").build();
    }

    CollectionBatch collectPage(int page, LocalDate cutoff) {
        String listHtml = client.get().uri(uri -> uri.path(LIST_PATH).queryParam("pageIndex", page).build())
                .retrieve().body(String.class);
        List<ListItem> items = parseList(listHtml);
        List<IndustryPolicyRepository.SourceDocument> documents = new ArrayList<>();
        boolean requestedDetail = false;
        for (ListItem item : items) {
            if (cutoff != null && item.publishedAt() != null && item.publishedAt().isBefore(cutoff)) continue;
            if (!IndustryPolicyService.mightMatchAnyProfile(item.title() + " " + item.lead())) continue;
            if (requestedDetail) pause();
            requestedDetail = true;
            String html = client.get().uri(URI.create(item.link())).retrieve().body(String.class);
            Detail detail = parseDetail(html, item);
            if (detail == null) continue;
            documents.add(new IndustryPolicyRepository.SourceDocument(
                    SOURCE, item.externalId(), detail.title(), detail.content(), item.link(),
                    detail.publishedAt(), Instant.now(), detail.publisher(), detail.licenseType()));
        }
        LocalDate oldest = items.stream().map(ListItem::publishedAt).filter(java.util.Objects::nonNull)
                .min(LocalDate::compareTo).orElse(null);
        return new CollectionBatch(List.copyOf(documents), oldest, items.size());
    }

    static List<ListItem> parseList(String html) {
        if (html == null || html.isBlank()) throw new IllegalArgumentException("정책브리핑 목록이 비어 있습니다.");
        var document = Jsoup.parse(html, "https://" + HOST);
        List<ListItem> items = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Element link : document.select(".list_type > ul > li > a[href*='policyNewsView.do?newsId=']")) {
            String safeLink = safeLink(link.absUrl("href"));
            String externalId = externalId(safeLink);
            Element title = link.selectFirst(".text > strong");
            if (externalId == null || title == null || !ids.add(externalId)) continue;
            String lead = text(link.selectFirst(".text > .lead"));
            var source = link.select(".text > .source > span");
            LocalDate publishedAt = source.isEmpty() ? null : parseDate(source.getFirst().text());
            String publisher = source.size() < 2 ? "대한민국 정책브리핑" : cleanPublisher(source.get(1).text());
            items.add(new ListItem(externalId, title.text().trim(), lead, safeLink, publishedAt, publisher));
            if (items.size() == MAX_LIST_ITEMS) break;
        }
        return List.copyOf(items);
    }

    static Detail parseDetail(String html, ListItem fallback) {
        if (html == null || html.isBlank()) return null;
        var document = Jsoup.parse(html);
        String licenseType = allowedLicense(document.select(".kogl_wrap .type").text());
        if (licenseType == null) return null;
        Element body = document.selectFirst(".article_body .view_cont");
        if (body == null) return null;
        Element copy = body.clone();
        copy.select("img, picture, figure, figcaption, video, audio, iframe, script, style, .imageWrap, .videoWrap")
                .remove();
        String content = copy.text().replaceAll("\\s+", " ").trim();
        if (content.isBlank()) return null;
        if (content.length() > 6000) content = content.substring(0, 6000);
        String title = text(document.selectFirst(".view_title h1"));
        var info = document.select(".article_head .info > span");
        LocalDate publishedAt = info.isEmpty() ? fallback.publishedAt() : parseDate(info.getFirst().text());
        String publisher = info.size() < 2 ? fallback.publisher() : cleanPublisher(info.get(1).text());
        if (publisher.isBlank()) publisher = fallback.publisher();
        return new Detail(title.isBlank() ? fallback.title() : title, content, publishedAt, publisher, licenseType);
    }

    static String safeLink(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !HOST.equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || !DETAIL_PATH.equals(uri.getPath())) return null;
            String id = queryNewsId(uri.getRawQuery());
            return id == null ? null : "https://" + HOST + DETAIL_PATH + "?newsId=" + id;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static String externalId(String value) {
        String link = safeLink(value);
        return link == null ? null : queryNewsId(URI.create(link).getRawQuery());
    }

    private static String queryNewsId(String query) {
        if (query == null) return null;
        for (String part : query.split("&")) {
            if (part.startsWith("newsId=")) {
                String id = part.substring("newsId=".length());
                return id.matches("[0-9]{6,20}") ? id : null;
            }
        }
        return null;
    }

    private static String allowedLicense(String text) {
        String compact = text.replaceAll("\\s+", "");
        if (compact.contains("제0유형")) return "KOGL_TYPE_0";
        if (compact.contains("제1유형")) return "KOGL_TYPE_1";
        if (compact.contains("인공지능(AI)유형") || compact.contains("인공지능유형")) return "KOGL_AI";
        return null;
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value.trim(), DateTimeFormatter.ofPattern("yyyy.MM.dd"));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String text(Element element) {
        return element == null ? "" : element.text().replaceAll("\\s+", " ").trim();
    }

    private static String cleanPublisher(String value) {
        return value.replace("부처별 뉴스 이동", "").replaceAll("\\s+", " ").trim();
    }

    private static void pause() {
        try {
            Thread.sleep(DETAIL_DELAY_MILLIS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("정책브리핑 수집 대기가 중단되었습니다.", error);
        }
    }

    record ListItem(String externalId, String title, String lead, String link, LocalDate publishedAt,
                    String publisher) { }
    record Detail(String title, String content, LocalDate publishedAt, String publisher, String licenseType) { }
    record CollectionBatch(List<IndustryPolicyRepository.SourceDocument> documents, LocalDate oldestPublishedAt,
                           int listItemCount) { }
}
