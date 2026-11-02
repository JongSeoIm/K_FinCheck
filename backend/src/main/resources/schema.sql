-- 지원 종목 마스터 (종목코드는 앞자리 0 보존을 위해 문자열)
CREATE TABLE IF NOT EXISTS stock_info (
    symbol         VARCHAR(6)   NOT NULL PRIMARY KEY,
    name           VARCHAR(100) NOT NULL,
    market         VARCHAR(20)  NOT NULL,
    dart_corp_code VARCHAR(8)   NULL,
    use_yn         CHAR(1)      NOT NULL DEFAULT 'Y',
    regdate        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
