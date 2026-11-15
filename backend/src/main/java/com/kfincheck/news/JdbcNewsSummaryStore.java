package com.kfincheck.news;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/** MariaDB news_summary 테이블. 만료된 요약문은 읽거나 쓸 때마다 지운다. */
@Repository
public class JdbcNewsSummaryStore implements NewsSummaryStore {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public JdbcNewsSummaryStore(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("db/news-summary.sql")).execute(dataSource);
        purge(Instant.now());
    }

    @Override
    public Optional<NewsService.Summary> find(String link, Instant now) {
        purge(now);
        return jdbc.query("SELECT * FROM news_summary WHERE link_hash=? AND expires_at>?", (r, n) -> new NewsService.Summary(
                r.getString("link"), r.getString("title"), r.getString("source_name"), r.getString("attribution"),
                instant(r.getTimestamp("published_at")), r.getString("summary"), r.getString("mode"),
                instant(r.getTimestamp("generated_at")), r.getString("notice"), true,
                instant(r.getTimestamp("expires_at"))), hash(link), Timestamp.from(now)).stream().findFirst();
    }

    @Override
    public NewsService.Summary save(NewsService.Summary summary, Instant now) {
        purge(now);
        Instant expiresAt = now.plus(TTL);
        jdbc.update("""
                INSERT INTO news_summary(link_hash,link,title,source_name,attribution,published_at,summary,mode,notice,generated_at,expires_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE title=VALUES(title),source_name=VALUES(source_name),attribution=VALUES(attribution),
                 published_at=VALUES(published_at),summary=VALUES(summary),mode=VALUES(mode),notice=VALUES(notice),
                 generated_at=VALUES(generated_at),expires_at=VALUES(expires_at)""",
                hash(summary.link()), cut(summary.link(), 500), cut(summary.title(), 500), cut(summary.sourceName(), 50),
                cut(summary.attribution(), 100), timestamp(summary.publishedAt()), summary.summary(), cut(summary.mode(), 20),
                cut(summary.notice(), 300), Timestamp.from(summary.generatedAt() == null ? now : summary.generatedAt()),
                Timestamp.from(expiresAt));
        return summary.withExpiresAt(expiresAt);
    }

    private void purge(Instant now) {
        jdbc.update("DELETE FROM news_summary WHERE expires_at<=?", Timestamp.from(now));
    }

    static String hash(String link) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(link.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String cut(String value, int max) {
        String text = value == null ? "" : value;
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
