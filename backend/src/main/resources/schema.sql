-- 기업·종목 정보 (팀 ERD 초안 FKC_ERD_001 의 company 테이블 + 업종 컬럼)
-- 지원 종목을 늘리려면 이 테이블에 행을 추가하세요 (1분 안에 반영).
CREATE TABLE IF NOT EXISTS company (
    stock_code      VARCHAR(6)   NOT NULL COMMENT '종목 코드',
    company_name    VARCHAR(100) NOT NULL COMMENT '기업명',
    market_type     VARCHAR(20)  NOT NULL COMMENT '시장 구분(KOSPI, KOSDAQ 등)',
    industry        VARCHAR(50)  NULL     COMMENT '업종 (화면 표시용, 초안에 추가 제안)',
    dart_corp_code  VARCHAR(8)   NULL     COMMENT 'DART 기업 고유번호',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록 일시',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정 일시',
    PRIMARY KEY (stock_code),
    UNIQUE KEY uk_company_dart_corp_code (dart_corp_code)
) DEFAULT CHARSET = utf8mb4 COMMENT = '기업·종목 정보';

-- 회원 계정 (비밀번호는 BCrypt 해시만 저장)
CREATE TABLE IF NOT EXISTS app_user (
    user_id        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '회원 번호',
    login_id       VARCHAR(50)     NOT NULL COMMENT '로그인 ID',
    password_hash  VARCHAR(255)    NOT NULL COMMENT 'BCrypt 비밀번호 해시',
    nickname       VARCHAR(30)     NOT NULL COMMENT '닉네임',
    created_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '가입 일시',
    updated_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정 일시',
    PRIMARY KEY (user_id),
    UNIQUE KEY uk_app_user_login_id (login_id)
) DEFAULT CHARSET = utf8mb4 COMMENT = '회원 계정';

-- 사용자별 관심 종목 (같은 종목은 계정당 한 번만)
CREATE TABLE IF NOT EXISTS watchlist (
    user_id     BIGINT UNSIGNED NOT NULL COMMENT '회원 번호',
    stock_code  VARCHAR(6)      NOT NULL COMMENT '종목 코드',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '관심 종목 등록 일시',
    PRIMARY KEY (user_id, stock_code),
    KEY idx_watchlist_stock_code (stock_code),
    CONSTRAINT fk_watchlist_user FOREIGN KEY (user_id) REFERENCES app_user (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_watchlist_company FOREIGN KEY (stock_code) REFERENCES company (stock_code)
) DEFAULT CHARSET = utf8mb4 COMMENT = '사용자별 관심 종목';

-- OpenDART 공시 보고서 (같은 공시·같은 연결/별도 구분은 한 번만 저장)
CREATE TABLE IF NOT EXISTS financial_report (
    report_id      BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT COMMENT '공시 보고서 번호',
    stock_code     VARCHAR(6)        NOT NULL COMMENT '종목 코드',
    receipt_no     VARCHAR(14)       NOT NULL COMMENT 'DART 공시 접수번호',
    business_year  SMALLINT UNSIGNED NOT NULL COMMENT '사업연도',
    report_code    VARCHAR(5)        NOT NULL DEFAULT '11011' COMMENT '보고서 코드, 11011은 사업보고서',
    fs_div         VARCHAR(3)        NOT NULL DEFAULT 'CFS' COMMENT '재무제표 구분, CFS는 연결·OFS는 별도',
    collected_at   DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '수집 일시',
    PRIMARY KEY (report_id),
    UNIQUE KEY uk_financial_report (receipt_no, fs_div),
    KEY idx_financial_report_company_year (stock_code, business_year),
    CONSTRAINT fk_financial_report_company FOREIGN KEY (stock_code) REFERENCES company (stock_code)
) DEFAULT CHARSET = utf8mb4 COMMENT = 'OpenDART 공시 보고서';

-- 공시 재무 수치 (공시 하나에 계정 코드별 한 행, 값이 없으면 NULL)
CREATE TABLE IF NOT EXISTS financial_value (
    value_id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '재무 수치 번호',
    report_id            BIGINT UNSIGNED NOT NULL COMMENT '공시 보고서 번호',
    account_code         VARCHAR(50)     NOT NULL COMMENT '프로젝트 공통 계정 코드',
    account_name         VARCHAR(100)    NOT NULL COMMENT '프로젝트 공통 계정명',
    source_account_id    VARCHAR(200)    NULL     COMMENT 'DART 원본 계정 ID',
    source_account_name  VARCHAR(200)    NULL     COMMENT 'DART 원본 계정명',
    current_amount       DECIMAL(30,2)   NULL     COMMENT '당기 금액, 누락 시 NULL',
    previous_amount      DECIMAL(30,2)   NULL     COMMENT '전기 금액, 누락 시 NULL',
    currency             VARCHAR(3)      NOT NULL COMMENT '원본 통화',
    value_status         VARCHAR(30)     NULL     COMMENT 'AVAILABLE, MISSING, AMBIGUOUS, UNSUPPORTED_CURRENCY',
    PRIMARY KEY (value_id),
    UNIQUE KEY uk_financial_value (report_id, account_code),
    CONSTRAINT fk_financial_value_report FOREIGN KEY (report_id) REFERENCES financial_report (report_id) ON DELETE CASCADE
) DEFAULT CHARSET = utf8mb4 COMMENT = '공시 재무 수치';

-- 이전 버전 DB 에도 조회에 필요한 컬럼을 추가합니다 (있으면 건너뜀). 전체 맞춤은 backend/sql/erd_맞춤_변경.sql
ALTER TABLE financial_report
    ADD COLUMN IF NOT EXISTS collected_at DATETIME NULL COMMENT '수집 일시';

ALTER TABLE financial_value
    ADD COLUMN IF NOT EXISTS source_account_id VARCHAR(200) NULL COMMENT 'DART 원본 계정 ID',
    ADD COLUMN IF NOT EXISTS source_account_name VARCHAR(200) NULL COMMENT 'DART 원본 계정명',
    ADD COLUMN IF NOT EXISTS value_status VARCHAR(30) NULL COMMENT 'AVAILABLE, MISSING, AMBIGUOUS, UNSUPPORTED_CURRENCY';

-- 사용자·종목별 대화방
CREATE TABLE IF NOT EXISTS chat_conversation (
    conversation_id  CHAR(36)        NOT NULL COMMENT '대화방 번호(UUID)',
    user_id          BIGINT UNSIGNED NOT NULL COMMENT '회원 번호',
    stock_code       VARCHAR(6)      NOT NULL COMMENT '종목 코드',
    stock_name       VARCHAR(100)    NOT NULL COMMENT '대화 시작 시점의 기업명',
    title            VARCHAR(80)     NOT NULL DEFAULT '새 대화' COMMENT '대화 제목(첫 질문)',
    created_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 일시',
    updated_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '마지막 대화 일시',
    PRIMARY KEY (conversation_id),
    KEY idx_chat_conversation_owner (user_id, stock_code, updated_at),
    CONSTRAINT fk_chat_conversation_user FOREIGN KEY (user_id) REFERENCES app_user (user_id) ON DELETE CASCADE
) DEFAULT CHARSET = utf8mb4 COMMENT = '사용자·종목별 대화방';

-- 질문 및 응답 (삭제하기 전까지 전체를 보관하고, AI 에게는 최근 10개만 보냅니다)
CREATE TABLE IF NOT EXISTS chat_message (
    message_id       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '메시지 번호',
    conversation_id  CHAR(36)        NOT NULL COMMENT '대화방 번호',
    role             VARCHAR(12)     NOT NULL COMMENT 'user 또는 assistant',
    content          LONGTEXT        NOT NULL COMMENT '화면에 보여 주는 내용',
    context_content  TEXT            NOT NULL COMMENT 'AI 문맥으로 보내는 내용',
    response_json    LONGTEXT        NULL     COMMENT 'AI 응답 전체(출처, 앞 질문 정보 포함)',
    created_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 일시',
    PRIMARY KEY (message_id),
    KEY idx_chat_message_conversation (conversation_id, message_id),
    CONSTRAINT fk_chat_message_conversation FOREIGN KEY (conversation_id) REFERENCES chat_conversation (conversation_id) ON DELETE CASCADE
) DEFAULT CHARSET = utf8mb4 COMMENT = '질문 및 응답';
