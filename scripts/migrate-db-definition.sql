-- 기존 DB의 컬럼 정의를 팀 공통 테이블 정의로 맞춘다 (데이터 유지, 한 번만 실행)
-- 순서: migrate-column-names.sql 을 먼저 실행한 DB 에 실행한다. 새로 만드는 DB 는 실행하지 않는다.
-- 실행: mariadb -P 3300 -u root -p --default-character-set=utf8mb4 stockdb < scripts/migrate-db-definition.sql
-- 주의: 백엔드를 끈 상태에서 실행한다. 실행 전에 mariadb-dump 로 백업한다.

SET NAMES utf8mb4;

-- 참조 컬럼의 타입을 바꾸기 위해 외래 키를 잠시 지운다
ALTER TABLE watchlist DROP FOREIGN KEY fk_watchlist_user;
ALTER TABLE chat_conversation
    DROP FOREIGN KEY fk_chat_conversation_user,
    DROP FOREIGN KEY fk_chat_conversation_company;
ALTER TABLE financial_value DROP FOREIGN KEY fk_financial_value_report;

ALTER TABLE company
    DROP COLUMN use_yn,
    ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at,
    ADD CONSTRAINT uk_company_dart_corp_code UNIQUE (dart_corp_code);

ALTER TABLE app_user
    MODIFY COLUMN user_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    MODIFY COLUMN login_id VARCHAR(50) NOT NULL,
    MODIFY COLUMN password_hash VARCHAR(255) NOT NULL,
    MODIFY COLUMN nickname VARCHAR(30) NOT NULL,
    ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at;

ALTER TABLE watchlist
    MODIFY COLUMN user_id BIGINT UNSIGNED NOT NULL,
    ADD INDEX idx_watchlist_stock_code (stock_code),
    ADD CONSTRAINT fk_watchlist_user FOREIGN KEY (user_id) REFERENCES app_user (user_id) ON DELETE CASCADE;

ALTER TABLE financial_report
    DROP COLUMN created_at,
    MODIFY COLUMN report_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    MODIFY COLUMN business_year SMALLINT UNSIGNED NOT NULL,
    MODIFY COLUMN report_code VARCHAR(5) NOT NULL DEFAULT '11011',
    MODIFY COLUMN fs_div VARCHAR(3) NOT NULL DEFAULT 'CFS',
    ADD INDEX idx_financial_report_company_year (stock_code, business_year);

-- 이전 account_name 은 공시에 적힌 이름이었으므로 source_account_name 으로 옮기고, account_name 은 공통 이름으로 바꾼다
UPDATE financial_value SET account_code = LEFT(account_code, 50) WHERE CHAR_LENGTH(account_code) > 50;

ALTER TABLE financial_value
    MODIFY COLUMN value_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    MODIFY COLUMN report_id BIGINT UNSIGNED NOT NULL,
    MODIFY COLUMN account_code VARCHAR(50) NOT NULL,
    ADD COLUMN source_account_id VARCHAR(200) NULL AFTER account_name,
    ADD COLUMN source_account_name VARCHAR(200) NULL AFTER source_account_id,
    MODIFY COLUMN current_amount DECIMAL(30,2) NULL,
    MODIFY COLUMN previous_amount DECIMAL(30,2) NULL,
    MODIFY COLUMN currency VARCHAR(3) NOT NULL,
    MODIFY COLUMN value_status VARCHAR(30) NULL,
    ADD CONSTRAINT fk_financial_value_report FOREIGN KEY (report_id) REFERENCES financial_report (report_id) ON DELETE CASCADE;

UPDATE financial_value SET source_account_name = account_name;

UPDATE financial_value
SET account_name = CASE account_code
        WHEN 'REVENUE' THEN '매출액'
        WHEN 'OPERATING_INCOME' THEN '영업이익'
        WHEN 'PRETAX_INCOME' THEN '법인세차감전순이익'
        WHEN 'NET_INCOME' THEN '당기순이익'
        WHEN 'COMPREHENSIVE_INCOME' THEN '총포괄손익'
        WHEN 'CURRENT_ASSETS' THEN '유동자산'
        WHEN 'NON_CURRENT_ASSETS' THEN '비유동자산'
        WHEN 'TOTAL_ASSETS' THEN '자산총계'
        WHEN 'CURRENT_LIABILITIES' THEN '유동부채'
        WHEN 'NON_CURRENT_LIABILITIES' THEN '비유동부채'
        WHEN 'TOTAL_LIABILITIES' THEN '부채총계'
        WHEN 'CAPITAL_STOCK' THEN '자본금'
        WHEN 'RETAINED_EARNINGS' THEN '이익잉여금'
        WHEN 'TOTAL_EQUITY' THEN '자본총계'
        ELSE account_name
    END;

UPDATE financial_value SET value_status = 'AVAILABLE' WHERE value_status = 'OK';

UPDATE chat_conversation SET title = LEFT(title, 80) WHERE CHAR_LENGTH(title) > 80;

ALTER TABLE chat_conversation
    MODIFY COLUMN user_id BIGINT UNSIGNED NOT NULL,
    MODIFY COLUMN title VARCHAR(80) NOT NULL DEFAULT '새 대화',
    MODIFY COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD INDEX idx_chat_conversation_owner (user_id, stock_code, updated_at),
    DROP INDEX idx_chat_conversation_list,
    ADD CONSTRAINT fk_chat_conversation_user FOREIGN KEY (user_id) REFERENCES app_user (user_id) ON DELETE CASCADE;

-- 이전 context_content 는 어디에서도 읽지 않던 기록이므로 AI 문맥으로 보낼 내용으로 바꾼다
UPDATE chat_message SET context_content = LEFT(content, 16000);

ALTER TABLE chat_message
    MODIFY COLUMN message_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    MODIFY COLUMN role VARCHAR(12) NOT NULL,
    MODIFY COLUMN context_content TEXT NOT NULL,
    MODIFY COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD INDEX idx_chat_message_conversation (conversation_id, message_id);
