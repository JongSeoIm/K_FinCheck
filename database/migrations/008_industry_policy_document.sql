USE k_fincheck;

CREATE TABLE IF NOT EXISTS policy_document (
    policy_document_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    source VARCHAR(30) NOT NULL,
    external_id VARCHAR(64) NOT NULL,
    title VARCHAR(500) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    source_url VARCHAR(1024) NOT NULL,
    published_at DATE NULL,
    collected_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (policy_document_id),
    UNIQUE KEY uk_policy_document_source_external (source, external_id),
    KEY idx_policy_document_published (published_at, policy_document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
