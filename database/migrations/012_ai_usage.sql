USE k_fincheck;

-- AI 사용량 기록 (ChatUsageGuard). 백엔드 시작 시 db/ai-usage.sql 로도 자동 생성된다.
CREATE TABLE IF NOT EXISTS ai_usage_log (
 usage_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT UNSIGNED NOT NULL,
 conversation_id CHAR(36) NULL,
 status VARCHAR(20) NOT NULL,
 model VARCHAR(120) NULL,
 input_tokens INT UNSIGNED NOT NULL DEFAULT 0,
 output_tokens INT UNSIGNED NOT NULL DEFAULT 0,
 embedding_tokens INT UNSIGNED NOT NULL DEFAULT 0,
 llm_calls TINYINT UNSIGNED NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX idx_ai_usage_user_time (user_id, created_at),
 FOREIGN KEY (user_id) REFERENCES app_user(user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
