USE k_fincheck;

CREATE TABLE IF NOT EXISTS company_event (
    event_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    stock_code VARCHAR(6) NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    title VARCHAR(255) NOT NULL,
    event_date DATE NOT NULL,
    event_time TIME NULL,
    announced_date DATE NULL,
    source VARCHAR(30) NOT NULL,
    source_url VARCHAR(1024) NOT NULL,
    receipt_no VARCHAR(14) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (event_id),
    UNIQUE KEY uk_company_event_receipt (receipt_no),
    KEY idx_company_event_date (event_date),
    KEY idx_company_event_stock_date (stock_code, event_date),
    CONSTRAINT fk_company_event_company
        FOREIGN KEY (stock_code) REFERENCES company (stock_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
