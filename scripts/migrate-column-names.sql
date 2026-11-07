-- 기존 DB의 컬럼 이름을 개발예정보고서 기준으로 바꾼다 (데이터 유지, 한 번만 실행)
-- 새로 만드는 DB는 schema.sql 이 바로 새 이름으로 만들므로 실행하지 않는다.
-- 실행: mariadb -P 3300 -u root -p stockdb < scripts/migrate-column-names.sql
-- 주의: 백엔드를 끈 상태에서 실행한다 (실행 중인 서버는 옛 이름을 쓴다).

ALTER TABLE company
    RENAME COLUMN symbol TO stock_code,
    RENAME COLUMN name TO company_name,
    RENAME COLUMN market TO market_type,
    RENAME COLUMN regdate TO created_at;

ALTER TABLE app_user
    RENAME COLUMN regdate TO created_at;

ALTER TABLE watchlist
    RENAME COLUMN symbol TO stock_code,
    RENAME COLUMN regdate TO created_at;

ALTER TABLE financial_report
    RENAME COLUMN symbol TO stock_code,
    RENAME COLUMN rcept_no TO receipt_no,
    RENAME COLUMN bsns_year TO business_year,
    RENAME COLUMN reprt_code TO report_code,
    RENAME COLUMN regdate TO created_at;

-- 복합 PK(report_id, account_id) → value_id PK + UNIQUE(report_id, account_code)
ALTER TABLE financial_value
    RENAME COLUMN account_id TO account_code,
    RENAME COLUMN account_nm TO account_name,
    RENAME COLUMN thstrm_amount TO current_amount,
    RENAME COLUMN frmtrm_amount TO previous_amount,
    DROP PRIMARY KEY,
    ADD COLUMN value_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST,
    ADD CONSTRAINT uk_financial_value UNIQUE (report_id, account_code);

-- 대화방을 여러 개 둘 수 있도록 (user_id, symbol) UNIQUE 를 일반 인덱스로 바꾼다
ALTER TABLE chat_conversation
    RENAME COLUMN symbol TO stock_code,
    MODIFY COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    ADD INDEX idx_chat_conversation_list (user_id, stock_code, updated_at),
    DROP INDEX uk_chat_conversation;

ALTER TABLE chat_message
    ADD COLUMN context_content LONGTEXT NULL AFTER content;
