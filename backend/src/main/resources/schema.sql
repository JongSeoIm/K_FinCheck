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