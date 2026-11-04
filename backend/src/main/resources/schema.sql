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
