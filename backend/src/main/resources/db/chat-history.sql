CREATE TABLE IF NOT EXISTS chat_conversation (
 conversation_id CHAR(36) NOT NULL PRIMARY KEY,
 user_id BIGINT UNSIGNED NOT NULL,
 stock_code VARCHAR(6) NOT NULL,
 stock_name VARCHAR(100) NOT NULL,
 title VARCHAR(80) NOT NULL DEFAULT '새 대화',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX idx_chat_owner (user_id, stock_code, updated_at),
 FOREIGN KEY (user_id) REFERENCES app_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS chat_message (
 message_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 conversation_id CHAR(36) NOT NULL,
 role VARCHAR(12) NOT NULL,
 content LONGTEXT NOT NULL,
 context_content TEXT NOT NULL,
 response_json LONGTEXT DEFAULT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX idx_chat_messages (conversation_id, message_id),
 FOREIGN KEY (conversation_id) REFERENCES chat_conversation(conversation_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
