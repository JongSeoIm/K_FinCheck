CREATE TABLE IF NOT EXISTS news_summary (
 link_hash CHAR(64) NOT NULL PRIMARY KEY,
 link VARCHAR(500) NOT NULL,
 title VARCHAR(500) NOT NULL,
 source_name VARCHAR(50) NOT NULL,
 attribution VARCHAR(100) NOT NULL,
 published_at DATETIME(6) NULL,
 summary TEXT NOT NULL,
 mode VARCHAR(20) NOT NULL,
 notice VARCHAR(300) NOT NULL,
 generated_at DATETIME(6) NOT NULL,
 expires_at DATETIME(6) NOT NULL,
 INDEX idx_news_summary_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
