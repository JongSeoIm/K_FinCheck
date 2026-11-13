CREATE TABLE IF NOT EXISTS disclosure (
 receipt_no VARCHAR(14) NOT NULL,
 stock_code VARCHAR(6) NOT NULL,
 corp_code VARCHAR(8) NOT NULL,
 company_name VARCHAR(100) NULL,
 report_name VARCHAR(300) NOT NULL,
 filer_name VARCHAR(100) NULL,
 received_date DATE NOT NULL,
 remark VARCHAR(20) NULL,
 source_url VARCHAR(300) NOT NULL,
 collected_at DATETIME NOT NULL,
 PRIMARY KEY (receipt_no),
 KEY idx_disclosure_stock_date (stock_code, received_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS disclosure_sync (
 stock_code VARCHAR(6) NOT NULL,
 sync_date DATE NOT NULL,
 synced_at DATETIME NOT NULL,
 PRIMARY KEY (stock_code, sync_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
