package com.kfincheck.news;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * AI 요약문 단기 보관소. 기사 원문·본문·임베딩은 보관하지 않고 요약문과 출처 정보만 24시간 보관한다.
 */
public interface NewsSummaryStore {
    Duration TTL = Duration.ofHours(24);

    /** 보관 기간이 남은 요약문만 돌려준다. */
    Optional<NewsService.Summary> find(String link, Instant now);

    /** 요약문을 보관하고, 만료 시각을 넣은 요약을 돌려준다. */
    NewsService.Summary save(NewsService.Summary summary, Instant now);
}
