-- 로그인·계정별 관심종목용 테이블 (팀 테이블정의서의 app_user / watchlist)
-- 먼저 kfincheck_earnings.sql(company 테이블)을 실행한 뒤, 사용할 데이터베이스를 선택하고 실행하세요.
-- 여러 번 실행해도 안전합니다.

CREATE TABLE IF NOT EXISTS app_user (
    user_id       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '회원 번호',
    login_id      VARCHAR(50)     NOT NULL COMMENT '로그인 ID',
    password_hash VARCHAR(255)    NOT NULL COMMENT 'BCrypt 비밀번호 해시',
    nickname      VARCHAR(30)     NOT NULL COMMENT '닉네임',
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '가입 일시',
    updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정 일시',
    PRIMARY KEY (user_id),
    UNIQUE KEY uk_app_user_login_id (login_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='회원 계정';

CREATE TABLE IF NOT EXISTS watchlist (
    user_id    BIGINT UNSIGNED NOT NULL COMMENT '회원 번호 → app_user.user_id, 사용자 삭제 시 함께 삭제',
    stock_code VARCHAR(6)      NOT NULL COMMENT '종목 코드 → company.stock_code',
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '관심 종목 등록 일시',
    PRIMARY KEY (user_id, stock_code),
    CONSTRAINT fk_watchlist_user FOREIGN KEY (user_id) REFERENCES app_user (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_watchlist_company FOREIGN KEY (stock_code) REFERENCES company (stock_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='사용자별 관심 종목';

-- 관심 종목은 company 에 있는 종목만 등록할 수 있으므로, 지원 종목 3개를 미리 넣어 둡니다.
-- (이미 있으면 그대로 두고, 실적 조회 때 DART 법인명으로 갱신됩니다.)
INSERT IGNORE INTO company (stock_code, company_name, market_type, dart_corp_code) VALUES
    ('005930', '삼성전자',   'KOSPI', '00126380'),
    ('000660', 'SK하이닉스', 'KOSPI', '00164779'),
    ('035420', 'NAVER',      'KOSPI', '00266961');
