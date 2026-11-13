USE k_fincheck;

CREATE TABLE IF NOT EXISTS policy_collection_state (
    source VARCHAR(30) NOT NULL,
    next_page INT NOT NULL DEFAULT 2,
    backfill_from DATE NOT NULL,
    backfill_completed BOOLEAN NOT NULL DEFAULT FALSE,
    last_success_at DATETIME NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (source)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
