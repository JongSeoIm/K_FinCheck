package com.kfincheck.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NewsServiceTest {
    @Test
    void normalizesSearchSnippetsDeduplicatesLinksAndParsesPublicationTime() throws Exception {
        var items = new ObjectMapper().readTree("""
            [
              {"title":"<b>삼성전자</b> &amp; 실적","description":"성장 &quot;확인&quot;",
               "originallink":"https://example.com/article/1","pubDate":"Mon, 02 Nov 2026 15:00:00 +0900"},
              {"title":"중복 기사","originallink":"https://example.com/article/1"},
              {"title":"링크 대체","originallink":"javascript:alert(1)","link":"https://news.naver.com/article/2","pubDate":"invalid"},
              {"title":"위험한 주소","link":"data:text/html,bad"}
            ]
            """);
        var feed = NewsService.normalize("005930", "삼성전자", items);
        assertThat(feed.articles()).hasSize(2);
        assertThat(feed.articles().getFirst().title()).isEqualTo("삼성전자 & 실적");
        assertThat(feed.articles().getFirst().description()).isEqualTo("성장 \"확인\"");
        assertThat(feed.articles().getFirst().publishedAt()).hasToString("2026-11-02T06:00:00Z");
        assertThat(feed.articles().getLast().link()).isEqualTo("https://news.naver.com/article/2");
        assertThat(feed.articles().getLast().publishedAt()).isNull();
    }

    @Test
    void rejectsUnsafeSchemesAndCredentialBearingLinks() {
        assertThat(NewsService.safeLink("javascript:alert(1)")).isNull();
        assertThat(NewsService.safeLink("https://user:password@example.com/article")).isNull();
        assertThat(NewsService.safeLink("//example.com/article")).isNull();
        assertThat(NewsService.safeLink("https://example.com/article")).isEqualTo("https://example.com/article");
    }

    @Test
    void exposesClearScopeAndMissingConfigurationErrorsWithoutCallingProvider() throws Exception {
        var service = new NewsService(new NaverNewsConfig(), RestClient.builder());
        var mvc = MockMvcBuilders.standaloneSetup(new NewsController(service)).build();
        mvc.perform(get("/api/stocks/005930/news")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("NEWS_KEY_NOT_CONFIGURED"));
        mvc.perform(get("/api/stocks/unknown/news")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_STOCK"));
    }
}
