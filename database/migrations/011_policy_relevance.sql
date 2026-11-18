USE k_fincheck;

CREATE TABLE IF NOT EXISTS policy_relevance (
    policy_document_id BIGINT UNSIGNED NOT NULL,
    stock_code CHAR(6) NOT NULL,
    relevant BOOLEAN NOT NULL,
    relation_type VARCHAR(20) NOT NULL,
    relevance_score INT NOT NULL,
    embedding_similarity DECIMAL(7, 6) NULL,
    reason VARCHAR(500) NOT NULL,
    analyzer_version VARCHAR(50) NOT NULL,
    analyzed_at DATETIME NOT NULL,

    PRIMARY KEY (policy_document_id, stock_code),
    KEY idx_policy_relevance_stock (stock_code, relevant, relevance_score),
    CONSTRAINT fk_policy_relevance_document
        FOREIGN KEY (policy_document_id) REFERENCES policy_document(policy_document_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
