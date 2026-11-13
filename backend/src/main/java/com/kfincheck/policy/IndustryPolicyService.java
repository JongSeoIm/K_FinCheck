package com.kfincheck.policy;

import org.jsoup.Jsoup;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.xml.sax.InputSource;

@Service
public class IndustryPolicyService {
    private static final String HOST = "www.motir.go.kr";
    private static final String RSS_PATH = "/kor/article/ATCL3f49a5a8c/rss";
    private static final String ARTICLE_PATH = "/kor/article/ATCL3f49a5a8c/";
    private static final long MOTIR_REFRESH_SECONDS = 1800;
    private static final long BRIEFING_REFRESH_SECONDS = 21600;
    private static final int BACKFILL_MONTHS = 3;
    private static final Map<String, Profile> PROFILES = Map.of(
            "005930", new Profile("삼성전자", List.of("삼성전자"),
                    List.of("반도체", "HBM", "파운드리", "디스플레이", "전자산업", "첨단 디스플레이")),
            "000660", new Profile("SK하이닉스", List.of("SK하이닉스", "하이닉스"),
                    List.of("반도체", "HBM", "메모리", "DRAM", "낸드")),
            "035420", new Profile("NAVER", List.of("NAVER", "네이버"),
                    List.of("온라인 플랫폼", "디지털 플랫폼", "인공지능", "AI", "데이터", "클라우드")),
            "035720", new Profile("카카오", List.of("카카오"),
                    List.of("온라인 플랫폼", "디지털 플랫폼", "인공지능", "AI", "데이터", "콘텐츠")),
            "005380", new Profile("현대자동차", List.of("현대자동차", "현대차"),
                    List.of("자동차", "전기차", "수소차", "자율주행", "모빌리티", "자동차 부품")),
            "000270", new Profile("기아", List.of("기아자동차", "기아"),
                    List.of("자동차", "전기차", "수소차", "자율주행", "모빌리티", "자동차 부품")),
            "051910", new Profile("LG화학", List.of("LG화학"),
                    List.of("석유화학", "화학산업", "첨단소재", "배터리", "배터리 소재", "양극재", "이차전지")),
            "006400", new Profile("삼성SDI", List.of("삼성SDI"),
                    List.of("배터리", "이차전지", "전고체", "ESS", "양극재"))
    );

    private final RestClient client;
    private final IndustryPolicyRepository repository;
    private final PolicyBriefingCollector briefingCollector;
    private Instant briefingNextAttemptAt = Instant.EPOCH;
    private int briefingFailures;

    public IndustryPolicyService(RestClient.Builder builder, IndustryPolicyRepository repository) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        client = builder.baseUrl("https://" + HOST).requestFactory(factory).build();
        this.repository = repository;
        this.briefingCollector = new PolicyBriefingCollector(builder);
    }

    public synchronized PolicyFeed latest(String stockCode) {
        Profile profile = PROFILES.get(stockCode);
        if (profile == null) throw new PolicyException(HttpStatus.NOT_FOUND,
                "UNSUPPORTED_STOCK", "해당 종목의 산업·정책 동향은 아직 지원하지 않습니다.");
        List<IndustryPolicyRepository.AnalyzedDocument> documents = repository.findRelevant(
                stockCode, PolicyRelevanceService.ANALYZER_VERSION, 10);
        List<PolicyItem> policies = new ArrayList<>();
        for (IndustryPolicyRepository.AnalyzedDocument analyzed : documents) {
            IndustryPolicyRepository.StoredDocument document = analyzed.document();
            policies.add(new PolicyItem(document.title(), excerpt(document.content()), document.content(),
                    document.link(), document.publishedAt(), analyzed.relationType(), analyzed.relevanceScore(),
                    classifyStatus(document.title() + " " + document.content()), document.source(),
                    document.publisher(), document.licenseType(),
                    classifyTags(document.title() + " " + document.content())));
            if (policies.size() == 10) break;
        }
        Instant basis = documents.stream().map(item -> item.document().collectedAt())
                .max(Comparator.naturalOrder()).orElse(null);
        PolicyFeed feed = new PolicyFeed(stockCode, profile.name(), "산업통상부·대한민국 정책브리핑",
                basis, List.copyOf(policies));
        return feed;
    }

    public synchronized CollectionSummary collectScheduled() {
        Instant now = Instant.now();
        boolean motirCollected = false;
        boolean briefingLatestCollected = false;
        Integer backfillPage = null;
        boolean backfillCompleted = false;
        List<String> errors = new ArrayList<>();

        if (isDue("MOTIR", MOTIR_REFRESH_SECONDS, now)) {
            try {
                refreshMotirDocuments();
                motirCollected = true;
            } catch (PolicyException error) {
                errors.add(error.code());
            }
        }

        if (!now.isBefore(briefingNextAttemptAt)) {
            try {
                if (isDue(PolicyBriefingCollector.SOURCE, BRIEFING_REFRESH_SECONDS, now)) {
                    saveBriefingPage(1, null);
                    briefingLatestCollected = true;
                } else {
                    IndustryPolicyRepository.BackfillState state = repository.backfillState(
                            PolicyBriefingCollector.SOURCE,
                            LocalDate.now(ZoneId.of("Asia/Seoul")).minusMonths(BACKFILL_MONTHS));
                    if (!state.completed()) {
                        PolicyBriefingCollector.CollectionBatch batch = saveBriefingPage(
                                state.nextPage(), state.backfillFrom());
                        backfillPage = state.nextPage();
                        backfillCompleted = batch.listItemCount() == 0
                                || (batch.oldestPublishedAt() != null
                                && batch.oldestPublishedAt().isBefore(state.backfillFrom()));
                        repository.advanceBackfill(PolicyBriefingCollector.SOURCE, state.nextPage(),
                                backfillCompleted, Instant.now());
                    } else {
                        backfillCompleted = true;
                    }
                }
                briefingFailures = 0;
                briefingNextAttemptAt = Instant.EPOCH;
            } catch (RuntimeException error) {
                briefingFailures++;
                long delay = Math.min(86400L,
                        BRIEFING_REFRESH_SECONDS * (1L << Math.min(2, briefingFailures - 1)));
                briefingNextAttemptAt = now.plusSeconds(delay);
                errors.add("KOREA_KR_COLLECTION_ERROR");
            }
        }

        return new CollectionSummary(motirCollected, briefingLatestCollected, backfillPage,
                backfillCompleted, List.copyOf(errors));
    }

    private boolean isDue(String source, long intervalSeconds, Instant now) {
        return repository.latestCollectedAt(source)
                .map(collectedAt -> !now.isBefore(collectedAt.plusSeconds(intervalSeconds)))
                .orElse(true);
    }

    private void refreshMotirDocuments() {
        Corpus corpus = loadCorpus();
        List<IndustryPolicyRepository.SourceDocument> documents = new ArrayList<>();
        for (FeedItem item : corpus.items()) {
            String content = fetchContent(item.link());
            String externalId = externalId(item.link());
            if (content.isBlank() || externalId == null) continue;
            documents.add(new IndustryPolicyRepository.SourceDocument("MOTIR", externalId, item.title(), content,
                    item.link(), item.publishedAt(), corpus.fetchedAt(), "산업통상부", null));
        }
        if (documents.isEmpty()) {
            throw new PolicyException(HttpStatus.BAD_GATEWAY, "POLICY_CONTENT_ERROR",
                    "산업·정책 원문을 가져오지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
        repository.saveAll(documents);
    }

    private PolicyBriefingCollector.CollectionBatch saveBriefingPage(int page, LocalDate cutoff) {
        PolicyBriefingCollector.CollectionBatch batch = briefingCollector.collectPage(page, cutoff);
        if (!batch.documents().isEmpty()) repository.saveAll(batch.documents());
        return batch;
    }

    private Corpus loadCorpus() {
        try {
            String xml = client.post().uri(RSS_PATH).retrieve().body(String.class);
            return new Corpus(Instant.now(), parseFeed(xml));
        } catch (Exception error) {
            throw new PolicyException(HttpStatus.BAD_GATEWAY, "POLICY_PROVIDER_ERROR",
                    "산업·정책 자료를 가져오지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    private String fetchContent(String link) {
        try {
            URI uri = URI.create(link);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !HOST.equalsIgnoreCase(uri.getHost())
                    || !uri.getPath().startsWith(ARTICLE_PATH) || !uri.getPath().endsWith("/view")) return "";
            String html = client.get().uri(uri).retrieve().body(String.class);
            return extractContent(html);
        } catch (Exception ignored) {
            return "";
        }
    }

    static List<FeedItem> parseFeed(String xml) throws Exception {
        if (xml == null || xml.isBlank()) throw new IllegalArgumentException("RSS 응답이 비어 있습니다.");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        var nodes = document.getElementsByTagName("item");
        List<FeedItem> items = new ArrayList<>();
        Set<String> links = new HashSet<>();
        for (int index = 0; index < nodes.getLength(); index++) {
            var element = (org.w3c.dom.Element) nodes.item(index);
            String title = text(element, "title");
            String link = safeLink(text(element, "link"));
            if (title.isBlank() || link == null || !links.add(link)) continue;
            LocalDate publishedAt;
            try { publishedAt = LocalDate.parse(text(element, "pubDate")); }
            catch (RuntimeException ignored) { publishedAt = null; }
            items.add(new FeedItem(title, link, publishedAt));
        }
        return List.copyOf(items);
    }

    private static String text(org.w3c.dom.Element element, String tag) {
        var nodes = element.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().replaceAll("\\s+", " ").trim();
    }

    static String safeLink(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && HOST.equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null && uri.getPath().startsWith(ARTICLE_PATH)
                    && uri.getPath().endsWith("/view") ? uri.toASCIIString() : null;
        } catch (RuntimeException ignored) { return null; }
    }

    static String externalId(String value) {
        String link = safeLink(value);
        if (link == null) return null;
        String path = URI.create(link).getPath();
        String id = path.substring(ARTICLE_PATH.length(), path.length() - "/view".length());
        return id.matches("[A-Za-z0-9_-]{1,64}") ? id : null;
    }

    static String extractContent(String html) {
        if (html == null || html.isBlank()) return "";
        var body = Jsoup.parse(html).selectFirst(".detail-cont.mViewerContents");
        if (body == null) return "";
        String text = body.text().replaceAll("\\s+", " ").trim();
        return text.length() > 6000 ? text.substring(0, 6000) : text;
    }

    static Relevance relevance(Profile profile, String title, String content) {
        String normalizedTitle = title.toLowerCase(Locale.ROOT);
        String normalizedContent = content.toLowerCase(Locale.ROOT);
        String normalizedLead = normalizedContent.substring(0, Math.min(600, normalizedContent.length()));
        boolean directTitle = profile.aliases().stream().anyMatch(alias -> containsTerm(normalizedTitle, alias));
        if (directTitle) return new Relevance("DIRECT", 100);
        long titleMatches = profile.keywords().stream().filter(keyword -> containsTerm(normalizedTitle, keyword)).count();
        long leadMatches = profile.keywords().stream().filter(keyword -> containsTerm(normalizedLead, keyword)).count();
        boolean directContent = profile.aliases().stream().anyMatch(alias -> containsTerm(normalizedContent, alias))
                && (titleMatches > 0 || profile.aliases().stream().anyMatch(alias -> containsTerm(normalizedLead, alias))
                && leadMatches > 0);
        if (directContent) return new Relevance("DIRECT", titleMatches > 0 ? 90 : 70);
        long contentMatches = profile.keywords().stream().filter(keyword -> containsTerm(normalizedContent, keyword)).count();
        if (titleMatches == 0 && leadMatches < 2) return new Relevance("INDIRECT", 0);
        int score = (int) Math.min(85, 50 + titleMatches * 12 + leadMatches * 6 + contentMatches * 2);
        return new Relevance("INDIRECT", score);
    }

    static Map<String, Profile> profiles() { return PROFILES; }

    static boolean mightMatchAnyProfile(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return PROFILES.values().stream().anyMatch(profile ->
                profile.aliases().stream().anyMatch(alias -> containsTerm(normalized, alias))
                        || profile.keywords().stream().anyMatch(keyword -> containsTerm(normalized, keyword)));
    }

    private static boolean containsTerm(String text, String term) {
        String normalized = term.toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9]+")) return text.contains(normalized);
        return java.util.regex.Pattern.compile("(?<![a-z0-9])" + java.util.regex.Pattern.quote(normalized)
                + "(?![a-z0-9])").matcher(text).find();
    }

    static String classifyStatus(String text) {
        String compact = text.replaceAll("\\s+", "");
        if (compact.matches(".*(시행중|시행됐다|시행되었다|시행한다).*")) return "IN_EFFECT";
        if (compact.matches(".*(시행예정|시행할예정|추진할계획|제정할계획|개정할계획).*")) return "PLANNED";
        if (compact.matches(".*(확정했다|확정됐다|확정되었다|의결했다|의결됐다|의결되었다).*")) return "CONFIRMED";
        return "ANNOUNCED";
    }

    static List<String> classifyTags(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        addTag(tags, normalized, "반도체", "반도체", "hbm", "dram", "낸드", "파운드리");
        addTag(tags, normalized, "AI", "인공지능", "ai", "피지컬 ai");
        addTag(tags, normalized, "플랫폼", "플랫폼", "클라우드", "데이터 산업");
        addTag(tags, normalized, "자동차", "자동차", "전기차", "수소차", "자율주행", "모빌리티");
        addTag(tags, normalized, "배터리", "배터리", "이차전지", "전고체", "ess", "양극재");
        addTag(tags, normalized, "화학", "석유화학", "화학산업", "첨단소재");
        addTag(tags, normalized, "지원", "지원", "육성", "보조금");
        addTag(tags, normalized, "규제", "규제", "의무", "제한");
        addTag(tags, normalized, "세제", "세액공제", "세제", "조세");
        addTag(tags, normalized, "투자", "투자", "펀드", "금융지원");
        addTag(tags, normalized, "수출·통상", "수출", "통상", "관세", "무역", "해외진출");
        addTag(tags, normalized, "연구개발", "연구개발", "r&d", "기술개발");
        return tags.stream().limit(3).toList();
    }

    private static void addTag(Set<String> tags, String text, String label, String... terms) {
        if (Arrays.stream(terms).anyMatch(term -> containsTerm(text, term))) tags.add(label);
    }

    private static String excerpt(String content) {
        if (content == null || content.isBlank()) return "상세 내용은 원문에서 확인해 주세요.";
        return content.length() > 280 ? content.substring(0, 280) + "…" : content;
    }

    record Profile(String name, List<String> aliases, List<String> keywords) { }
    record FeedItem(String title, String link, LocalDate publishedAt) { }
    record Relevance(String type, int score) { }
    record Corpus(Instant fetchedAt, List<FeedItem> items) { }
    public record PolicyItem(String title, String summary, String content, String link, LocalDate publishedAt,
                             String relationType, int relevanceScore, String policyStatus, String source,
                             String publisher, String licenseType, List<String> tags) { }
    public record PolicyFeed(String stockCode, String companyName, String source, Instant fetchedAt,
                             List<PolicyItem> policies) { }
    public record CollectionSummary(boolean motirCollected, boolean briefingLatestCollected,
                                    Integer backfillPage, boolean backfillCompleted, List<String> errors) { }

    public static class PolicyException extends RuntimeException {
        private final HttpStatus status;
        private final String code;
        public PolicyException(HttpStatus status, String code, String message) {
            super(message); this.status = status; this.code = code;
        }
        public HttpStatus status() { return status; }
        public String code() { return code; }
    }
}
