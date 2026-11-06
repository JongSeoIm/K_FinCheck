-- 테이블·컬럼 정의는 팀 공통 테이블 정의(개발예정보고서, ERD 초안)를 따른다

-- 기업·종목 정보 (종목코드는 앞자리 0 보존을 위해 문자열)
CREATE TABLE IF NOT EXISTS company (
    stock_code     VARCHAR(6)   NOT NULL PRIMARY KEY,
    company_name   VARCHAR(100) NOT NULL,
    market_type    VARCHAR(20)  NOT NULL,
    dart_corp_code VARCHAR(8)   NULL,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_company_dart_corp_code UNIQUE (dart_corp_code)
) DEFAULT CHARSET = utf8mb4;

-- 회원 계정 (비밀번호는 BCrypt 해시만 저장)
CREATE TABLE IF NOT EXISTS app_user (
    user_id       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    login_id      VARCHAR(50)     NOT NULL,
    password_hash VARCHAR(255)    NOT NULL,
    nickname      VARCHAR(30)     NOT NULL,
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_app_user_login_id UNIQUE (login_id)
) DEFAULT CHARSET = utf8mb4;

-- 계정별 관심 종목 (같은 종목 중복 등록 방지)
CREATE TABLE IF NOT EXISTS watchlist (
    user_id    BIGINT UNSIGNED NOT NULL,
    stock_code VARCHAR(6)      NOT NULL,
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, stock_code),
    INDEX idx_watchlist_stock_code (stock_code),
    CONSTRAINT fk_watchlist_user FOREIGN KEY (user_id) REFERENCES app_user (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_watchlist_company FOREIGN KEY (stock_code) REFERENCES company (stock_code)
) DEFAULT CHARSET = utf8mb4;

-- OpenDART 공시 (어떤 공시인지 기록)
-- report_code: 11013 1분기 / 11012 반기 / 11014 3분기 / 11011 사업보고서
-- fs_div: CFS 연결 / OFS 별도, collected_at: 수집 시각 (1시간이 지나면 다시 수집)
CREATE TABLE IF NOT EXISTS financial_report (
    report_id     BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT PRIMARY KEY,
    stock_code    VARCHAR(6)        NOT NULL,
    receipt_no    VARCHAR(14)       NOT NULL,
    business_year SMALLINT UNSIGNED NOT NULL,
    report_code   VARCHAR(5)        NOT NULL DEFAULT '11011',
    fs_div        VARCHAR(3)        NOT NULL DEFAULT 'CFS',
    collected_at  DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_financial_report UNIQUE (receipt_no, fs_div),
    INDEX idx_financial_report_company_year (stock_code, business_year),
    CONSTRAINT fk_financial_report_company FOREIGN KEY (stock_code) REFERENCES company (stock_code)
) DEFAULT CHARSET = utf8mb4;

-- 공시의 재무 수치 (매출액·영업이익·당기순이익 등)
-- account_code·account_name: 프로젝트 공통 계정 (REVENUE, 매출액)
-- source_account_id·source_account_name: DART 원본 계정 (주요계정 API 는 계정 ID 를 주지 않아 ID 는 NULL)
-- value_status: AVAILABLE / MISSING (AMBIGUOUS·UNSUPPORTED 는 팀 공통 값이며 지금은 쓰지 않는다)
CREATE TABLE IF NOT EXISTS financial_value (
    value_id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    report_id           BIGINT UNSIGNED NOT NULL,
    account_code        VARCHAR(50)     NOT NULL,
    account_name        VARCHAR(100)    NOT NULL,
    source_account_id   VARCHAR(200)    NULL,
    source_account_name VARCHAR(200)    NULL,
    current_amount      DECIMAL(30,2)   NULL,
    previous_amount     DECIMAL(30,2)   NULL,
    currency            VARCHAR(3)      NOT NULL,
    value_status        VARCHAR(30)     NULL,
    CONSTRAINT uk_financial_value UNIQUE (report_id, account_code),
    CONSTRAINT fk_financial_value_report FOREIGN KEY (report_id) REFERENCES financial_report (report_id) ON DELETE CASCADE
) DEFAULT CHARSET = utf8mb4;

-- 사용자·종목별 대화방 (한 종목에 여러 개, title: 첫 질문)
CREATE TABLE IF NOT EXISTS chat_conversation (
    conversation_id CHAR(36)        NOT NULL PRIMARY KEY,
    user_id         BIGINT UNSIGNED NOT NULL,
    stock_code      VARCHAR(6)      NOT NULL,
    stock_name      VARCHAR(100)    NOT NULL,
    title           VARCHAR(80)     NOT NULL DEFAULT '새 대화',
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_chat_conversation_owner (user_id, stock_code, updated_at),
    CONSTRAINT fk_chat_conversation_user FOREIGN KEY (user_id) REFERENCES app_user (user_id) ON DELETE CASCADE
) DEFAULT CHARSET = utf8mb4;

-- 대화 메시지
-- content: 화면에 보여 주는 내용, context_content: AI 문맥으로 보내는 내용
-- response_json: AI 응답의 status·sources·visuals
CREATE TABLE IF NOT EXISTS chat_message (
    message_id      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id CHAR(36)        NOT NULL,
    role            VARCHAR(12)     NOT NULL,
    content         LONGTEXT        NOT NULL,
    context_content TEXT            NOT NULL,
    response_json   LONGTEXT        NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_chat_message_conversation (conversation_id, message_id),
    CONSTRAINT fk_chat_message_conversation FOREIGN KEY (conversation_id) REFERENCES chat_conversation (conversation_id) ON DELETE CASCADE
) DEFAULT CHARSET = utf8mb4;
