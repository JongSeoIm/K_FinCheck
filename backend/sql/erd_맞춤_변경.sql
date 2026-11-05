-- 이전 버전으로 만든 DB 를 ERD 와 같은 구조로 바꿉니다. 기존 데이터는 유지합니다.
-- 백엔드를 끈 뒤 한 번 실행합니다. 다시 실행해도 안전합니다.
USE stock_db;

ALTER TABLE watchlist DROP FOREIGN KEY IF EXISTS fk_watchlist_user;
ALTER TABLE IF EXISTS chat_conversation DROP FOREIGN KEY IF EXISTS fk_chat_conversation_user;
ALTER TABLE financial_value DROP FOREIGN KEY IF EXISTS fk_financial_value_report;

ALTER TABLE app_user
    MODIFY user_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '회원 번호',
    MODIFY login_id VARCHAR(50) NOT NULL COMMENT '로그인 ID',
    MODIFY password_hash VARCHAR(255) NOT NULL COMMENT 'BCrypt 비밀번호 해시',
    MODIFY nickname VARCHAR(30) NOT NULL COMMENT '닉네임',
    MODIFY created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '가입 일시',
    ADD COLUMN IF NOT EXISTS updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정 일시';

ALTER TABLE watchlist
    MODIFY user_id BIGINT UNSIGNED NOT NULL COMMENT '회원 번호',
    MODIFY created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '관심 종목 등록 일시',
    ADD CONSTRAINT fk_watchlist_user FOREIGN KEY (user_id) REFERENCES app_user (user_id) ON DELETE CASCADE;

ALTER TABLE IF EXISTS chat_conversation
    ADD CONSTRAINT fk_chat_conversation_user FOREIGN KEY (user_id) REFERENCES app_user (user_id) ON DELETE CASCADE;

-- 수집 시각이 없는 자료는 오래된 시각으로 두어 다음 조회 때 다시 수집합니다
UPDATE financial_report SET collected_at = '2000-01-01 00:00:00' WHERE collected_at IS NULL;

ALTER TABLE financial_report
    MODIFY report_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '공시 보고서 번호',
    MODIFY stock_code VARCHAR(6) NOT NULL COMMENT '종목 코드' AFTER report_id,
    MODIFY business_year SMALLINT UNSIGNED NOT NULL COMMENT '사업연도',
    MODIFY report_code VARCHAR(5) NOT NULL DEFAULT '11011' COMMENT '보고서 코드, 11011은 사업보고서',
    MODIFY fs_div VARCHAR(3) NOT NULL DEFAULT 'CFS' COMMENT '재무제표 구분, CFS는 연결·OFS는 별도',
    MODIFY collected_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '수집 일시',
    DROP COLUMN IF EXISTS created_at,
    COMMENT = 'OpenDART 공시 보고서';

ALTER TABLE financial_value
    MODIFY value_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '재무 수치 번호',
    MODIFY report_id BIGINT UNSIGNED NOT NULL COMMENT '공시 보고서 번호',
    MODIFY account_code VARCHAR(50) NOT NULL COMMENT '프로젝트 공통 계정 코드',
    MODIFY account_name VARCHAR(100) NOT NULL COMMENT '프로젝트 공통 계정명',
    MODIFY source_account_id VARCHAR(200) NULL COMMENT 'DART 원본 계정 ID' AFTER account_name,
    MODIFY source_account_name VARCHAR(200) NULL COMMENT 'DART 원본 계정명' AFTER source_account_id,
    MODIFY current_amount DECIMAL(30,2) NULL COMMENT '당기 금액, 누락 시 NULL' AFTER source_account_name,
    MODIFY previous_amount DECIMAL(30,2) NULL COMMENT '전기 금액, 누락 시 NULL' AFTER current_amount,
    MODIFY currency VARCHAR(3) NOT NULL COMMENT '원본 통화' AFTER previous_amount,
    MODIFY value_status VARCHAR(30) NULL COMMENT 'AVAILABLE, MISSING, AMBIGUOUS, UNSUPPORTED_CURRENCY' AFTER currency,
    DROP COLUMN IF EXISTS source_statement,
    ADD CONSTRAINT fk_financial_value_report FOREIGN KEY (report_id) REFERENCES financial_report (report_id) ON DELETE CASCADE;
