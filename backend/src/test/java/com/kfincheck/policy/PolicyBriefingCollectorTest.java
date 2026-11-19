package com.kfincheck.policy;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyBriefingCollectorTest {
    @Test
    void parsesOnlyOfficialPolicyNewsLinksFromTheMainList() {
        var items = PolicyBriefingCollector.parseList("""
                <div class="list_type"><ul><li>
                  <a href="/news/policyNewsView.do?newsId=148975521">
                    <span class="text"><strong>반도체 지원 정책</strong><span class="lead">AI 반도체 지원 내용</span>
                    <span class="source"><span>2026.11.11</span><span>과학기술정보통신부</span></span></span>
                  </a>
                </li><li><a href="https://example.com/news?newsId=123456"><span class="text"><strong>외부</strong></span></a></li></ul></div>
                """);

        assertThat(items).hasSize(1);
        assertThat(items.getFirst().externalId()).isEqualTo("148975521");
        assertThat(items.getFirst().publishedAt()).isEqualTo(LocalDate.parse("2026-11-11"));
        assertThat(items.getFirst().publisher()).isEqualTo("과학기술정보통신부");
    }

    @Test
    void keepsTextForTypeOneAndRemovesImagesAndCaptions() {
        var fallback = new PolicyBriefingCollector.ListItem("148975521", "목록 제목", "요약",
                "https://www.korea.kr/news/policyNewsView.do?newsId=148975521",
                LocalDate.parse("2026-11-11"), "과학기술정보통신부");
        var detail = PolicyBriefingCollector.parseDetail("""
                <div class="view_title"><h1>상세 제목</h1></div>
                <div class="article_head"><div class="info"><span>2026.11.12</span><span>산업통상부<span>부처별 뉴스 이동</span></span></div></div>
                <div class="article_body"><div class="view_cont"><p>저장할 정책 본문</p>
                  <div class="imageWrap"><img src="photo.jpg"><figcaption>저장하지 않을 사진 설명</figcaption></div>
                </div></div>
                <div class="kogl_wrap"><div class="type">텍스트에 한하여 공공누리 제1유형(출처표시)</div></div>
                """, fallback);

        assertThat(detail).isNotNull();
        assertThat(detail.content()).isEqualTo("저장할 정책 본문");
        assertThat(detail.licenseType()).isEqualTo("KOGL_TYPE_1");
        assertThat(detail.publisher()).isEqualTo("산업통상부");
    }

    @Test
    void rejectsNoDerivativeAndMissingLicenseDocuments() {
        var fallback = new PolicyBriefingCollector.ListItem("148975521", "제목", "요약",
                "https://www.korea.kr/news/policyNewsView.do?newsId=148975521", null, "정책브리핑");
        String body = "<div class='article_body'><div class='view_cont'>본문</div></div>";

        assertThat(PolicyBriefingCollector.parseDetail(body
                + "<div class='kogl_wrap'><div class='type'>공공누리 제3유형 변경금지</div></div>", fallback)).isNull();
        assertThat(PolicyBriefingCollector.parseDetail(body, fallback)).isNull();
    }

    @Test
    void acceptsOnlyCanonicalPolicyBriefingDetailUrls() {
        assertThat(PolicyBriefingCollector.safeLink(
                "https://www.korea.kr/news/policyNewsView.do?newsId=148975521&pageIndex=1"))
                .isEqualTo("https://www.korea.kr/news/policyNewsView.do?newsId=148975521");
        assertThat(PolicyBriefingCollector.safeLink(
                "https://example.com/news/policyNewsView.do?newsId=148975521")).isNull();
    }
}
