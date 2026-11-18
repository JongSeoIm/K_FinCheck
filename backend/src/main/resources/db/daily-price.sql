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
