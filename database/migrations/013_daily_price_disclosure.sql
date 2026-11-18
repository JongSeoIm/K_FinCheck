-- 일봉 시세·공시 목록 저장 (챗봇이 날짜로 DB 조회)
-- Spring 시작 시 db/daily-price.sql, db/disclosure.sql 로 자동 생성되므로 수동 실행은 선택이다.
USE k_fincheck;

CREATE TABLE IF NOT EXISTS stock_daily_price (
 stock_code VARCHAR(6) NOT NULL,
 trade_date DATE NOT NULL,
 open_price BIGINT NULL,
 high_price BIGINT NULL,
 low_price BIGINT NULL,
 close_price BIGINT NOT NULL,
 volume BIGINT NULL,
 collected_at DATETIME NOT NULL,
 PRIMARY KEY (stock_code, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
