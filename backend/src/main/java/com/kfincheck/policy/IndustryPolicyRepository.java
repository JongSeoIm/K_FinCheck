package com.kfincheck.policy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class IndustryPolicyRepository {
    private final JdbcTemplate jdbc;

    public IndustryPolicyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public void saveAll(List<SourceDocument> documents) {
        for (SourceDocument document : documents) {
            jdbc.update("""
                    INSERT INTO policy_document
                        (source, external_id, title, content, source_url, published_at, collected_at,
                         publisher, license_type)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        title = VALUES(title), content = VALUES(content), source_url = VALUES(source_url),
                        published_at = VALUES(published_at), collected_at = VALUES(collected_at),
                        publisher = VALUES(publisher), license_type = VALUES(license_type)
                    """, document.source(), document.externalId(), document.title(), document.content(),
                    document.link(), document.publishedAt(), document.collectedAt(), document.publisher(),
                    document.licenseType());
        }
    }

    public List<StoredDocument> findRecent(int limit) {
        return jdbc.query("""
                SELECT policy_document_id, source, external_id, title, content, source_url, published_at, collected_at,
                       publisher, license_type
                FROM policy_document
                ORDER BY published_at DESC, policy_document_id DESC
                LIMIT ?
                """, (row, index) -> new StoredDocument(
                row.getLong("policy_document_id"), row.getString("source"), row.getString("external_id"), row.getString("title"),
                row.getString("content"), row.getString("source_url"),
                row.getObject("published_at", LocalDate.class), row.getTimestamp("collected_at").toInstant(),
                row.getString("publisher"), row.getString("license_type")), limit);
    }

    public List<AnalyzedDocument> findRelevant(String stockCode, String analyzerVersion, int limit) {
        return jdbc.query("""
                SELECT d.policy_document_id, d.source, d.external_id, d.title, d.content, d.source_url,
                       d.published_at, d.collected_at, d.publisher, d.license_type,
                       r.relation_type, r.relevance_score
                FROM policy_document d
                JOIN policy_relevance r ON r.policy_document_id = d.policy_document_id
                WHERE r.stock_code = ? AND r.relevant = TRUE AND r.analyzer_version = ?
                ORDER BY d.published_at DESC, r.relevance_score DESC, d.policy_document_id DESC
                LIMIT ?
                """, (row, index) -> new AnalyzedDocument(new StoredDocument(
                row.getLong("policy_document_id"), row.getString("source"), row.getString("external_id"),
                row.getString("title"), row.getString("content"), row.getString("source_url"),
                row.getObject("published_at", LocalDate.class), row.getTimestamp("collected_at").toInstant(),
                row.getString("publisher"), row.getString("license_type")), row.getString("relation_type"),
                row.getInt("relevance_score")), stockCode, analyzerVersion, limit);
    }

    public boolean hasRelevance(long documentId, String stockCode, String analyzerVersion) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM policy_relevance
                WHERE policy_document_id = ? AND stock_code = ? AND analyzer_version = ?
                """, Integer.class, documentId, stockCode, analyzerVersion);
        return count != null && count > 0;
    }

    public void saveRelevance(long documentId, String stockCode, RelevanceAnalysis analysis) {
        jdbc.update("""
                INSERT INTO policy_relevance
                    (policy_document_id, stock_code, relevant, relation_type, relevance_score,
                     embedding_similarity, reason, analyzer_version, analyzed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    relevant = VALUES(relevant), relation_type = VALUES(relation_type),
                    relevance_score = VALUES(relevance_score),
                    embedding_similarity = VALUES(embedding_similarity), reason = VALUES(reason),
                    analyzer_version = VALUES(analyzer_version), analyzed_at = VALUES(analyzed_at)
                """, documentId, stockCode, analysis.relevant(), analysis.relationType(),
                analysis.relevanceScore(), analysis.embeddingSimilarity(), analysis.reason(),
                analysis.analyzerVersion(), analysis.analyzedAt());
    }

    public Optional<Instant> latestCollectedAt(String source) {
        return jdbc.query("SELECT MAX(collected_at) AS collected_at FROM policy_document WHERE source = ?",
                result -> {
                    if (!result.next() || result.getTimestamp("collected_at") == null) return Optional.empty();
                    return Optional.of(result.getTimestamp("collected_at").toInstant());
                }, source);
    }

    @Transactional
    public BackfillState backfillState(String source, LocalDate backfillFrom) {
        jdbc.update("""
                INSERT IGNORE INTO policy_collection_state
                    (source, next_page, backfill_from, backfill_completed)
                VALUES (?, 2, ?, FALSE)
                """, source, backfillFrom);
        return jdbc.queryForObject("""
                SELECT next_page, backfill_from, backfill_completed
                FROM policy_collection_state
                WHERE source = ?
                """, (row, index) -> new BackfillState(row.getInt("next_page"),
                row.getObject("backfill_from", LocalDate.class), row.getBoolean("backfill_completed")), source);
    }

    public void advanceBackfill(String source, int completedPage, boolean completed, Instant succeededAt) {
        jdbc.update("""
                UPDATE policy_collection_state
                SET next_page = ?, backfill_completed = ?, last_success_at = ?
                WHERE source = ? AND next_page = ?
                """, completedPage + 1, completed, succeededAt, source, completedPage);
    }

    public record SourceDocument(String source, String externalId, String title, String content, String link,
                                 LocalDate publishedAt, Instant collectedAt, String publisher,
                                 String licenseType) { }
    public record StoredDocument(long id, String source, String externalId, String title, String content, String link,
                                 LocalDate publishedAt, Instant collectedAt, String publisher,
                                 String licenseType) { }
    public record AnalyzedDocument(StoredDocument document, String relationType, int relevanceScore) { }
    public record RelevanceAnalysis(boolean relevant, String relationType, int relevanceScore,
                                    Double embeddingSimilarity, String reason, String analyzerVersion,
                                    Instant analyzedAt) { }
    public record BackfillState(int nextPage, LocalDate backfillFrom, boolean completed) { }
}
