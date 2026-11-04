-- 기업·종목 정보 (종목코드는 앞자리 0 보존을 위해 문자열)
-- 1차의 stock_info 테이블을 2차 문서 이름에 맞춰 company 로 바꿨다
CREATE TABLE IF NOT EXISTS company (
    symbol         VARCHAR(6)   NOT NULL PRIMARY KEY,
    name           VARCHAR(100) NOT NULL,
    market         VARCHAR(20)  NOT NULL,
    dart_corp_code VARCHAR(8)   NULL,
    use_yn         CHAR(1)      NOT NULL DEFAULT 'Y',
    regdate        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 회원 계정 (비밀번호는 BCrypt 해시만 저장)
CREATE TABLE IF NOT EXISTS app_user (
    user_id       BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    login_id      VARCHAR(20)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    nickname      VARCHAR(20)  NOT NULL,
    regdate       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_app_user_login_id UNIQUE (login_id)
);

-- 계정별 관심 종목 (같은 종목 중복 등록 방지)
CREATE TABLE IF NOT EXISTS watchlist (
    user_id BIGINT     NOT NULL,
    symbol  VARCHAR(6) NOT NULL,
    regdate DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, symbol),
    CONSTRAINT fk_watchlist_user FOREIGN KEY (user_id) REFERENCES app_user (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_watchlist_company FOREIGN KEY (symbol) REFERENCES company (symbol)
);

-- OpenDART 공시 (어떤 공시인지 기록)
-- reprt_code: 11013 1분기 / 11012 반기 / 11014 3분기 / 11011 사업보고서
-- fs_div: CFS 연결 / OFS 별도
CREATE TABLE IF NOT EXISTS financial_report (
    report_id  BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    rcept_no   VARCHAR(14) NOT NULL,
    symbol     VARCHAR(6)  NOT NULL,
    bsns_year  CHAR(4)     NOT NULL,
    reprt_code VARCHAR(5)  NOT NULL,
    fs_div     VARCHAR(3)  NOT NULL,
    regdate    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_financial_report UNIQUE (rcept_no, fs_div),
    CONSTRAINT fk_financial_report_company FOREIGN KEY (symbol) REFERENCES company (symbol)
);

-- 공시의 재무 수치 (매출액·영업이익·당기순이익 등)
CREATE TABLE IF NOT EXISTS financial_value (
    report_id     BIGINT       NOT NULL,
    account_id    VARCHAR(100) NOT NULL,
    account_nm    VARCHAR(100) NOT NULL,
    thstrm_amount BIGINT       NULL,
    frmtrm_amount BIGINT       NULL,
    currency      VARCHAR(3)   NOT NULL DEFAULT 'KRW',
    PRIMARY KEY (report_id, account_id),
    CONSTRAINT fk_financial_value_report FOREIGN KEY (report_id) REFERENCES financial_report (report_id) ON DELETE CASCADE
);
