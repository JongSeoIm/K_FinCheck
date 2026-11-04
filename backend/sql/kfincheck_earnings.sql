-- K-FinCheck 실적 저장용 테이블 (팀 테이블정의서의 company / financial_report / financial_value)
-- HeidiSQL 에서 사용할 데이터베이스를 먼저 선택한 뒤 실행하세요. 여러 번 실행해도 안전합니다.

CREATE TABLE IF NOT EXISTS company (
    stock_code     VARCHAR(6)   NOT NULL COMMENT '종목 코드',
    company_name   VARCHAR(100) NOT NULL COMMENT '기업명',
    market_type    VARCHAR(20)  NOT NULL COMMENT '시장 구분(KOSPI, KOSDAQ 등)',
    dart_corp_code VARCHAR(8)   NULL     COMMENT 'DART 기업 고유번호',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록 일시',
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정 일시',
    PRIMARY KEY (stock_code),
    UNIQUE KEY uk_company_dart_corp_code (dart_corp_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='기업·종목 정보';

CREATE TABLE IF NOT EXISTS financial_report (
    report_id     BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT COMMENT '공시 보고서 번호',
    stock_code    VARCHAR(6)        NOT NULL COMMENT '종목 코드 → company.stock_code',
    receipt_no    VARCHAR(14)       NOT NULL COMMENT 'DART 공시 접수번호, fs_div와 복합 UK',
    business_year SMALLINT UNSIGNED NOT NULL COMMENT '사업연도',
    report_code   VARCHAR(5)        NOT NULL DEFAULT '11011' COMMENT '보고서 코드, 11011은 사업보고서',
    fs_div        VARCHAR(3)        NOT NULL DEFAULT 'CFS' COMMENT '재무제표 구분, CFS는 연결·OFS는 별도',
    collected_at  DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '수집 일시',
    PRIMARY KEY (report_id),
    UNIQUE KEY uk_financial_report_receipt_fs (receipt_no, fs_div),
    KEY idx_financial_report_stock (stock_code, business_year),
    CONSTRAINT fk_financial_report_company FOREIGN KEY (stock_code) REFERENCES company (stock_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OpenDART 공시 보고서';

CREATE TABLE IF NOT EXISTS financial_value (
    value_id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '재무 수치 번호',
    report_id           BIGINT UNSIGNED NOT NULL COMMENT '공시 보고서 번호 → financial_report.report_id',
    account_code        VARCHAR(50)     NOT NULL COMMENT '프로젝트 공통 계정 코드, report_id와 복합 UK',
    account_name        VARCHAR(100)    NOT NULL COMMENT '프로젝트 공통 계정명',
    source_account_id   VARCHAR(200)    NULL COMMENT 'DART 원본 계정 ID',
    source_account_name VARCHAR(200)    NULL COMMENT 'DART 원본 계정명',
    current_amount      DECIMAL(30,2)   NULL COMMENT '당기 금액, 누락 시 NULL',
    previous_amount     DECIMAL(30,2)   NULL COMMENT '전기 금액, 누락 시 NULL',
    currency            VARCHAR(3)      NOT NULL COMMENT '원본 통화(KRW 등)',
    value_status        VARCHAR(30)     NULL COMMENT 'AVAILABLE, MISSING, AMBIGUOUS, UNSUPPORTED_CURRENCY',
    PRIMARY KEY (value_id),
    UNIQUE KEY uk_financial_value_report_account (report_id, account_code),
    CONSTRAINT fk_financial_value_report FOREIGN KEY (report_id) REFERENCES financial_report (report_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='공시 재무 수치';
