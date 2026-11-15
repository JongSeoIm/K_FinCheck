-- 기존 재무 수치와 테이블을 보존하며 상태만 추가한다.
USE k_fincheck;
ALTER TABLE financial_value
    ADD COLUMN IF NOT EXISTS value_status VARCHAR(30) NULL
    COMMENT 'AVAILABLE, MISSING, AMBIGUOUS, UNSUPPORTED_CURRENCY';
