package com.kfincheck.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NewsServiceTest {
    private static final String FEED = """
        {"stockCode":"005930","companyName":"삼성전자","source":"조선비즈",
         "basis":"관련도는 본문 분석이 아닌 제목 키워드 기준입니다.","summaryAvailable":true,"summaryNotice":"",
         "fetchedAt":"2026-11-12T05:00:00+00:00",
         "articles":[
          {"title":"삼성전자 HBM 공급","link":"https://biz.chosun.com/a/1","sourceName":"조선비즈",
           "publishedAt":"2026-11-12T04:00:00+00:00","relevance":"direct","relevanceLabel":"직접 관련"},
          {"title":"반도체 업황","link":"https://biz.chosun.com/a/2","sourceName":"조선비즈",
           "publishedAt":null,"relevance":"industry","relevanceLabel":"산업 관련"},
          {"title":"케이크 예약","link":"https://biz.chosun.com/a/3","relevance":"none","relevanceLabel":""},
          {"title":"중복","link":"https://biz.chosun.com/a/1","relevance":"direct"},
          {"title":"위험한 주소","link":"javascript:alert(1)","relevance":"direct"}]}
        """;
    private static final String FEED_URL = "http://ai/api/news/feed?stock=005930";
    private MockRestServiceServer server;
    private MockMvc mvc;
    private NewsService service;
    private final Map<String, NewsService.Summary> saved = new HashMap<>();

    /** DB 대신 쓰는 보관소: 만료 시각이 지난 요약문은 돌려주지 않는다. */
    private final NewsSummaryStore store = new NewsSummaryStore() {
        public Optional<NewsService.Summary> find(String link, Instant now) {
            var item = saved.get(link);
            return item != null && item.expiresAt().isAfter(now)
                    ? Optional.of(new NewsService.Summary(item.link(), item.title(), item.sourceName(), item.attribution(),
                        item.publishedAt(), item.summary(), item.mode(), item.generatedAt(), item.notice(), true, item.expiresAt()))
                    : Optional.empty();
        }
        public NewsService.Summary save(NewsService.Summary summary, Instant now) {
            var stored = summary.withExpiresAt(now.plus(TTL));
            saved.put(summary.link(), stored);
            return stored;
        }
    };

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai");
        server = MockRestServiceServer.bindTo(builder).build();
        service = new NewsService(builder.build(), store);
        mvc = MockMvcBuilders.standaloneSetup(new NewsController(service)).build();
    }

    @Test
    void normalizesWholeListWithRelevanceLabels() throws Exception {
        var feed = NewsService.normalize("005930", new ObjectMapper().readTree(FEED));
        assertThat(feed.articles()).extracting(NewsService.Article::relevance).containsExactly("direct", "industry", "none");
        assertThat(feed.articles().getFirst().description()).isEmpty();
        assertThat(feed.articles().get(1).publishedAt()).isNull();
        assertThat(feed.basis()).contains("제목 키워드 기준");
    }

    @Test
    void rejectsOtherStockAndUnsafeLinks() {
        assertThatThrownBy(() -> NewsService.normalize("000660", new ObjectMapper().readTree(FEED)))
                .isInstanceOf(NewsService.NewsException.class);
        assertThat(NewsService.safeLink("javascript:alert(1)")).isNull();
        assertThat(NewsService.safeLink("https://user:pw@biz.chosun.com/a")).isNull();
        assertThat(NewsService.safeLink("https://biz.chosun.com/a")).isEqualTo("https://biz.chosun.com/a");
    }

    @Test
    void listsFeedAndCachesIt() throws Exception {
        server.expect(once(), requestTo(FEED_URL)).andRespond(withSuccess(FEED, MediaType.APPLICATION_JSON));
        mvc.perform(get("/api/stocks/005930/news")).andExpect(status().isOk())
                .andExpect(jsonPath("$.articles.length()").value(3))
                .andExpect(jsonPath("$.articles[0].relevanceLabel").value("직접 관련"))
                .andExpect(jsonPath("$.summaryAvailable").value(true))
                .andExpect(jsonPath("$.cachedAt").doesNotExist());
        mvc.perform(get("/api/stocks/005930/news")).andExpect(status().isOk());
        server.verify();
    }

    @Test
    void summarizesListedArticleOnceAndKeepsItInStoreForOneDay() {
        server.expect(once(), requestTo(FEED_URL)).andRespond(withSuccess(FEED, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://ai/api/news/summary")).andExpect(method(HttpMethod.POST))
                .andExpect(MockRestRequestMatchers.jsonPath("$.link").value("https://biz.chosun.com/a/1"))
                .andExpect(MockRestRequestMatchers.jsonPath("$.userKey").value("user-7"))
                .andRespond(withSuccess("""
                    {"summary":"삼성전자가 HBM을 공급한다.","sourceName":"조선비즈","attribution":"출처: 조선비즈",
                     "publishedAt":"2026-11-12T04:00:00+00:00","mode":"openai",
                     "generatedAt":"2026-11-12T05:01:00+00:00","notice":"AI 요약입니다.","cached":false}
                    """, MediaType.APPLICATION_JSON));
        var first = service.summarize("005930", "https://biz.chosun.com/a/1", "user-7");
        assertThat(first.summary()).isEqualTo("삼성전자가 HBM을 공급한다.");
        assertThat(first.cached()).isFalse();
        assertThat(first.expiresAt()).isAfter(Instant.now().plusSeconds(23 * 3600));
        var second = service.summarize("005930", "https://biz.chosun.com/a/1", "user-8");   // AI 서버를 다시 부르지 않는다
        assertThat(second.cached()).isTrue();
        assertThat(service.storedSummary("https://biz.chosun.com/a/1")).isPresent();
        assertThatThrownBy(() -> service.summarize("005930", "https://example.com/other", "user-7"))
                .isInstanceOfSatisfying(NewsService.NewsException.class, e -> assertThat(e.code()).isEqualTo("ARTICLE_NOT_FOUND"));
        server.verify();
    }

    @Test
    void expiredSummaryIsNotReused() {
        saved.put("https://biz.chosun.com/a/2", new NewsService.Summary("https://biz.chosun.com/a/2", "반도체 업황", "조선비즈",
                "출처: 조선비즈", null, "예전 요약", "openai", Instant.now().minusSeconds(90000), "", false,
                Instant.now().minusSeconds(3600)));
        assertThat(service.storedSummary("https://biz.chosun.com/a/2")).isEmpty();
    }

    @Test
    void passesThroughLimitAndTermsErrors() {
        server.expect(requestTo(FEED_URL)).andRespond(withSuccess(FEED, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://ai/api/news/summary")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"detail\":{\"code\":\"DAILY_LIMIT\",\"error\":\"AI 요약은 하루 20건까지 요청할 수 있습니다.\"}}"));
        server.expect(requestTo("http://ai/api/news/summary")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"detail\":{\"code\":\"TERMS_CHECK_FAILED\",\"error\":\"잠시 후 다시 시도해 주세요.\"}}"));
        server.expect(requestTo("http://ai/api/news/summary")).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"detail\":{\"code\":\"TERMS_CHANGED\",\"error\":\"요약을 중단했습니다.\"}}"));
        for (String[] expected : new String[][]{{"429", "DAILY_LIMIT"}, {"503", "TERMS_CHECK_FAILED"}, {"403", "TERMS_CHANGED"}}) {
            assertThatThrownBy(() -> service.summarize("005930", "https://biz.chosun.com/a/1", "user-7"))
                    .isInstanceOfSatisfying(NewsService.NewsException.class, e -> {
                        assertThat(e.status().value()).isEqualTo(Integer.parseInt(expected[0]));
                        assertThat(e.code()).isEqualTo(expected[1]);
                    });
        }
        assertThat(saved).isEmpty();   // 실패한 요청은 보관하지 않는다
    }

    @Test
    void summaryButtonRequiresLoginAndUsesStoredSummary() throws Exception {
        mvc.perform(post("/api/stocks/005930/news/summary").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"link\":\"https://biz.chosun.com/a/1\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
        var user = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("tester", null, java.util.List.of());
        server.expect(once(), requestTo(FEED_URL)).andRespond(withSuccess(FEED, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://ai/api/news/summary"))
                .andExpect(MockRestRequestMatchers.jsonPath("$.userKey").value("tester"))
                .andRespond(withSuccess("{\"summary\":\"삼성전자가 HBM을 공급한다.\",\"attribution\":\"출처: 조선비즈\",\"mode\":\"openai\"}",
                        MediaType.APPLICATION_JSON));
        for (boolean cached : new boolean[]{false, true}) {   // 두 번째 클릭은 DB 보관분(AI 서버 호출 없음)
            mvc.perform(post("/api/stocks/005930/news/summary").principal(user).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"link\":\"https://biz.chosun.com/a/1\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.summary").value("삼성전자가 HBM을 공급한다."))
                    .andExpect(jsonPath("$.cached").value(cached)).andExpect(jsonPath("$.expiresAt").exists());
        }
        server.verify();
    }

    @Test
    void reportsUnsupportedStockAndConnectionErrors() throws Exception {
        mvc.perform(get("/api/stocks/unknown/news")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_STOCK"));
        server.expect(requestTo("http://ai/api/news/feed?stock=000660"))
                .andRespond(request -> { throw new java.net.ConnectException("refused"); });
        mvc.perform(get("/api/stocks/000660/news")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("NEWS_UNAVAILABLE"));
    }
}
