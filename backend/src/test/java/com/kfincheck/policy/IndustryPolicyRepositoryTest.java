package com.kfincheck.policy;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IndustryPolicyRepositoryTest {
    @Test
    void savesDocumentsWithoutDuplicatingTheSameOfficialItem() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:policy;MODE=MariaDB;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE policy_document (
                    policy_document_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    source VARCHAR(30) NOT NULL,
                    external_id VARCHAR(64) NOT NULL,
                    title VARCHAR(500) NOT NULL,
                    content TEXT NOT NULL,
                    source_url VARCHAR(1024) NOT NULL,
                    published_at DATE,
                    collected_at TIMESTAMP NOT NULL,
                    publisher VARCHAR(100),
                    license_type VARCHAR(30),
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE (source, external_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE policy_collection_state (
                    source VARCHAR(30) PRIMARY KEY,
                    next_page INT NOT NULL DEFAULT 2,
                    backfill_from DATE NOT NULL,
                    backfill_completed BOOLEAN NOT NULL DEFAULT FALSE,
                    last_success_at TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE policy_relevance (
                    policy_document_id BIGINT NOT NULL,
                    stock_code CHAR(6) NOT NULL,
                    relevant BOOLEAN NOT NULL,
                    relation_type VARCHAR(20) NOT NULL,
                    relevance_score INT NOT NULL,
                    embedding_similarity DECIMAL(7, 6),
                    reason VARCHAR(500) NOT NULL,
                    analyzer_version VARCHAR(50) NOT NULL,
                    analyzed_at TIMESTAMP NOT NULL,
                    PRIMARY KEY (policy_document_id, stock_code)
                )
                """);
        var repository = new IndustryPolicyRepository(jdbc);
        Instant first = Instant.parse("2026-11-11T00:00:00Z");
        Instant second = Instant.parse("2026-11-11T01:00:00Z");
        repository.saveAll(List.of(new IndustryPolicyRepository.SourceDocument(
                "MOTIR", "172390", "배터리 정책", "첫 본문",
                "https://www.motir.go.kr/kor/article/ATCL3f49a5a8c/172390/view",
                LocalDate.parse("2026-11-09"), first, "산업통상부", null)));
        repository.saveAll(List.of(new IndustryPolicyRepository.SourceDocument(
                "MOTIR", "172390", "배터리 정책 수정", "수정 본문",
                "https://www.motir.go.kr/kor/article/ATCL3f49a5a8c/172390/view",
                LocalDate.parse("2026-11-09"), second, "산업통상부", null)));

        var documents = repository.findRecent(10);
        assertThat(documents).hasSize(1);
        assertThat(documents.getFirst().title()).isEqualTo("배터리 정책 수정");
        assertThat(documents.getFirst().content()).isEqualTo("수정 본문");
        assertThat(documents.getFirst().collectedAt()).isEqualTo(second);
        assertThat(documents.getFirst().publisher()).isEqualTo("산업통상부");
        assertThat(repository.latestCollectedAt("MOTIR")).contains(second);
        long documentId = documents.getFirst().id();
        repository.saveRelevance(documentId, "006400", new IndustryPolicyRepository.RelevanceAnalysis(
                true, "INDIRECT", 81, 0.78, "배터리 산업 정책", "embedding-context-v1", second));
        assertThat(repository.hasRelevance(documentId, "006400", "embedding-context-v1")).isTrue();
        assertThat(repository.findRelevant("006400", "embedding-context-v1", 10)).hasSize(1);

        var state = repository.backfillState("KOREA_KR", LocalDate.parse("2026-08-12"));
        assertThat(state.nextPage()).isEqualTo(2);
        assertThat(state.completed()).isFalse();
        repository.advanceBackfill("KOREA_KR", 2, false, second);
        assertThat(repository.backfillState("KOREA_KR", LocalDate.parse("2026-08-12")).nextPage()).isEqualTo(3);
    }
}
