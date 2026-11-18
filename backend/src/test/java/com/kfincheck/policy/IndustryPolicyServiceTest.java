package com.kfincheck.policy;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class IndustryPolicyServiceTest {
    @Test
    void parsesOfficialRssAndRejectsUnsafeOrUnrelatedLinks() throws Exception {
        var items = IndustryPolicyService.parseFeed("""
                <?xml version="1.0" encoding="utf-8"?>
                <rss><channel>
                  <item><title><![CDATA[반도체 지원 정책 발표]]></title>
                    <link><![CDATA[https://www.motir.go.kr/kor/article/ATCL3f49a5a8c/172397/view]]></link>
                    <pubDate><![CDATA[2026-11-11]]></pubDate></item>
                  <item><title>외부 주소</title><link>https://example.com/article</link></item>
                </channel></rss>
                """);
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().title()).isEqualTo("반도체 지원 정책 발표");
        assertThat(items.getFirst().publishedAt()).hasToString("2026-11-11");
    }

    @Test
    void extractsOnlyOfficialDetailBodyAndLimitsLength() {
        String html = "<html><body><nav>메뉴</nav><div class='detail-cont mViewerContents'>정책 <b>본문</b></div></body></html>";
        assertThat(IndustryPolicyService.extractContent(html)).isEqualTo("정책 본문");
        assertThat(IndustryPolicyService.extractContent("<html><body>본문 없음</body></html>")).isEmpty();
    }

    @Test
    void separatesDirectAndIndustryRelationsAndPolicyStatus() {
        var profile = new IndustryPolicyService.Profile("삼성전자", java.util.List.of("삼성전자"),
                java.util.List.of("반도체", "HBM"));
        assertThat(IndustryPolicyService.relevance(profile, "삼성전자 지원", "").type()).isEqualTo("DIRECT");
        assertThat(IndustryPolicyService.relevance(profile, "반도체 세액공제 확대", "").score()).isGreaterThanOrEqualTo(50);
        assertThat(IndustryPolicyService.relevance(profile, "제조업 혁신", "HBM과 반도체 생산 지원").score()).isGreaterThanOrEqualTo(50);
        assertThat(IndustryPolicyService.relevance(profile, "제조업 혁신", "HBM 생산 지원").score()).isZero();
        assertThat(IndustryPolicyService.relevance(profile, "여성공학인 포상", "삼성전자 연구원이 수상했다").score()).isZero();
        assertThat(IndustryPolicyService.relevance(profile,
                "산업현장을 이끄는 힘, 여성공학인의 도전과 성장을 지원한다",
                "여성공학인대회와 포상 및 실태조사 추진 내용을 안내한다. ".repeat(20)
                        + "행사 후반 삼성전자 파운드리사업부 상무가 AI 시대의 반도체 산업을 주제로 강연했다.")
                .score()).isZero();
        assertThat(IndustryPolicyService.relevance(profile, "공급망 지원", "삼성전자의 반도체 공급망을 지원한다").type()).isEqualTo("DIRECT");
        assertThat(IndustryPolicyService.relevance(profile, "반도체 지원", "사업에 삼성전자가 참여한다").type()).isEqualTo("DIRECT");
        assertThat(IndustryPolicyService.relevance(profile, "섬유 산업 행사", "").score()).isZero();
        assertThat(IndustryPolicyService.classifyStatus("내년부터 시행할 예정입니다")).isEqualTo("PLANNED");
        assertThat(IndustryPolicyService.classifyStatus("지원 정책을 발표했습니다")).isEqualTo("ANNOUNCED");
        assertThat(IndustryPolicyService.classifyTags("반도체 산업의 세액공제와 연구개발 지원"))
                .containsExactly("반도체", "지원", "세제");
    }

    @Test
    void extractsStableExternalIdOnlyFromOfficialArticleLinks() {
        assertThat(IndustryPolicyService.externalId(
                "https://www.motir.go.kr/kor/article/ATCL3f49a5a8c/172390/view")).isEqualTo("172390");
        assertThat(IndustryPolicyService.externalId("https://example.com/172390/view")).isNull();
    }
}
