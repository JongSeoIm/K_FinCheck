package com.kfincheck.policy;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ChosunBizNewsServiceTest {
    @Test
    void parsesRssMetadataAndKeepsTheNewestItemForEachValidLink() {
        var articles = ChosunBizNewsService.parse("""
                <rss><channel>
                  <item><title>이전 제목</title><link>https://biz.chosun.com/stock/stock_general/2026/11/13/ARTICLE1/</link>
                    <pubDate>Fri, 13 Nov 2026 08:00:00 +0000</pubDate></item>
                  <item><title>삼성전자 &amp; 반도체</title><link>https://biz.chosun.com/stock/stock_general/2026/11/13/ARTICLE1/</link>
                    <pubDate>Fri, 13 Nov 2026 09:00:00 +0000</pubDate><description>본문</description></item>
                  <item><title>외부 주소</title><link>https://example.com/stock/stock_general/2026/11/13/ARTICLE1/</link>
                    <pubDate>Fri, 13 Nov 2026 10:00:00 +0000</pubDate></item>
                </channel></rss>
                """);
        assertThat(articles).singleElement().satisfies(article -> {
            assertThat(article.title()).isEqualTo("삼성전자 & 반도체");
            assertThat(article.publishedAt()).isEqualTo(Instant.parse("2026-11-13T09:00:00Z"));
        });
    }

    @Test
    void prefersCompanyMentionsAndIgnoresBroadStandaloneKeywords() {
        var profiles = IndustryPolicyService.profiles();
        assertThat(ChosunBizNewsService.relation(profiles.get("005930"), "삼성전자 반도체 투자")).isEqualTo("DIRECT");
        assertThat(ChosunBizNewsService.relation(profiles.get("000660"), "삼성전자 반도체 투자")).isEqualTo("INDUSTRY");
        assertThat(ChosunBizNewsService.relation(profiles.get("035420"), "AI 로봇 신제품 공개")).isNull();
    }
}
