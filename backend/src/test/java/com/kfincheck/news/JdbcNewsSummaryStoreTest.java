package com.kfincheck.news;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "stream.enabled=false")
@Transactional
class JdbcNewsSummaryStoreTest {
    @Autowired JdbcNewsSummaryStore store;
    @Autowired JdbcTemplate jdbc;

    @Test
    void keepsSummaryFor24HoursOnlyAndThenDeletesIt() {
        String link = "https://biz.chosun.com/test/" + java.util.UUID.randomUUID() + "/";
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var saved = store.save(new NewsService.Summary(link, "테스트 기사", "조선비즈", "출처: 조선비즈", null,
                "테스트 요약입니다.", "mock", now, "AI 요약입니다.", false, null), now);
        assertThat(saved.expiresAt()).isEqualTo(now.plus(Duration.ofHours(24)));

        var found = store.find(link, now.plusSeconds(23 * 3600)).orElseThrow();
        assertThat(found.summary()).isEqualTo("테스트 요약입니다.");
        assertThat(found.cached()).isTrue();

        assertThat(store.find(link, now.plusSeconds(24 * 3600 + 1))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_summary WHERE link=?", Integer.class, link)).isZero();
    }
}
