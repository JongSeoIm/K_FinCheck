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
    user_id        BIGINT       NOT NULL AUTO_INCREMENT COMMENT '회원 번호',
    login_id       VARCHAR(20)  NOT NULL COMMENT '로그인 ID',
    password_hash  VARCHAR(100) NOT NULL COMMENT '비밀번호 해시',
    nickname       VARCHAR(20)  NOT NULL COMMENT '닉네임',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '가입일',
    PRIMARY KEY (user_id),
    UNIQUE KEY uk_app_user_login_id (login_id)
) DEFAULT CHARSET = utf8mb4 COMMENT = '회원 계정';

-- 계정별 관심 종목 (같은 종목은 계정당 한 번만)
CREATE TABLE IF NOT EXISTS watchlist (
    user_id     BIGINT     NOT NULL COMMENT '회원 번호',
    stock_code  VARCHAR(6) NOT NULL COMMENT '종목 코드',
    created_at  DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록일',
    PRIMARY KEY (user_id, stock_code),
    KEY idx_watchlist_stock_code (stock_code),
    CONSTRAINT fk_watchlist_user FOREIGN KEY (user_id) REFERENCES app_user (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_watchlist_company FOREIGN KEY (stock_code) REFERENCES company (stock_code)
) DEFAULT CHARSET = utf8mb4 COMMENT = '계정별 관심 종목';

-- 어떤 공시인지 기록 (같은 공시·같은 연결/별도 구분은 한 번만 저장)
CREATE TABLE IF NOT EXISTS financial_report (
    report_id      BIGINT      NOT NULL AUTO_INCREMENT COMMENT '공시 번호',
    receipt_no     VARCHAR(14) NOT NULL COMMENT 'DART 공시 접수번호',
    stock_code     VARCHAR(6)  NOT NULL COMMENT '기업(종목 코드)',
    business_year  SMALLINT    NOT NULL COMMENT '사업연도',
    report_code    VARCHAR(5)  NOT NULL COMMENT '보고서 종류 (11011: 사업보고서)',
    fs_div         VARCHAR(3)  NOT NULL COMMENT '연결/별도 구분 (CFS: 연결, OFS: 별도)',
    collected_at   DATETIME    NULL     COMMENT '마지막 수집 시각',
    created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '저장 일시',
    PRIMARY KEY (report_id),
    UNIQUE KEY uk_financial_report (receipt_no, fs_div),
    KEY idx_financial_report_company_year (stock_code, business_year),
    CONSTRAINT fk_financial_report_company FOREIGN KEY (stock_code) REFERENCES company (stock_code)
) DEFAULT CHARSET = utf8mb4 COMMENT = '실적 공시';

-- 공시의 재무 수치 (공시 하나에 계정 코드별 한 행, 값이 없으면 NULL)
CREATE TABLE IF NOT EXISTS financial_value (
    value_id         BIGINT        NOT NULL AUTO_INCREMENT COMMENT '수치 번호',
    report_id        BIGINT        NOT NULL COMMENT '공시 번호',
    account_code     VARCHAR(50)   NOT NULL COMMENT '계정 코드',
    account_name     VARCHAR(100)  NOT NULL COMMENT '계정 이름',
    current_amount   DECIMAL(24,2) NULL     COMMENT '당기 금액',
    previous_amount  DECIMAL(24,2) NULL     COMMENT '전기 금액',
    currency         VARCHAR(3)    NOT NULL COMMENT '통화',
    source_account_id VARCHAR(150) NULL     COMMENT 'OpenDART 표준 계정 코드',
    source_account_name VARCHAR(100) NULL   COMMENT 'OpenDART 원본 계정명',
    source_statement VARCHAR(10)   NULL     COMMENT 'OpenDART 재무제표 구분 (IS, CIS 등)',
    value_status     VARCHAR(30)   NULL     COMMENT 'AVAILABLE, PARTIAL, MISSING, AMBIGUOUS, UNSUPPORTED_CURRENCY',
    PRIMARY KEY (value_id),
    UNIQUE KEY uk_financial_value (report_id, account_code),
    CONSTRAINT fk_financial_value_report FOREIGN KEY (report_id) REFERENCES financial_report (report_id) ON DELETE CASCADE
) DEFAULT CHARSET = utf8mb4 COMMENT = '공시 재무 수치';

-- 이미 만들어진 테이블에도 컬럼 추가 (있으면 건너뜀)
ALTER TABLE financial_report
    ADD COLUMN IF NOT EXISTS collected_at DATETIME NULL COMMENT '마지막 수집 시각' AFTER fs_div;

ALTER TABLE financial_value
    ADD COLUMN IF NOT EXISTS source_account_id VARCHAR(150) NULL COMMENT 'OpenDART 표준 계정 코드' AFTER currency,
    ADD COLUMN IF NOT EXISTS source_account_name VARCHAR(100) NULL COMMENT 'OpenDART 원본 계정명' AFTER source_account_id,
    ADD COLUMN IF NOT EXISTS source_statement VARCHAR(10) NULL COMMENT 'OpenDART 재무제표 구분 (IS, CIS 등)' AFTER source_account_name,
    ADD COLUMN IF NOT EXISTS value_status VARCHAR(30) NULL COMMENT 'AVAILABLE, PARTIAL, MISSING, AMBIGUOUS, UNSUPPORTED_CURRENCY' AFTER source_statement;