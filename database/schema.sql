-- K-FinCheck 프로젝트 테이블 구조. 실제 데이터·계정 비밀번호는 포함하지 않는다.
-- k_fincheck DB를 먼저 생성한 후 실행한다. 기존 테이블은 변경하지 않는다.
USE k_fincheck;

CREATE TABLE IF NOT EXISTS `app_user` (
  `user_id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `login_id` varchar(50) NOT NULL,
  `password_hash` varchar(255) NOT NULL,
  `nickname` varchar(30) NOT NULL,
  `created_at` datetime NOT NULL DEFAULT current_timestamp(),
  `updated_at` datetime NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `uk_app_user_login_id` (`login_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `company` (
  `stock_code` varchar(6) NOT NULL COMMENT '종목 코드',
  `company_name` varchar(100) NOT NULL,
  `market_type` varchar(20) NOT NULL COMMENT 'KOSPI, KOSDAQ 등',
  `dart_corp_code` varchar(8) DEFAULT NULL COMMENT 'DART 기업 고유번호',
  `created_at` datetime NOT NULL DEFAULT current_timestamp(),
  `updated_at` datetime NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`stock_code`),
  UNIQUE KEY `uk_company_dart_code` (`dart_corp_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `watchlist` (
  `user_id` bigint(20) unsigned NOT NULL,
  `stock_code` varchar(6) NOT NULL,
  `created_at` datetime NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`user_id`,`stock_code`),
  KEY `idx_watchlist_stock_code` (`stock_code`),
  CONSTRAINT `fk_watchlist_company` FOREIGN KEY (`stock_code`) REFERENCES `company` (`stock_code`),
  CONSTRAINT `fk_watchlist_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`user_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `financial_report` (
  `report_id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `stock_code` varchar(6) NOT NULL,
  `receipt_no` varchar(14) NOT NULL COMMENT 'DART 공시 접수번호',
  `business_year` smallint(5) unsigned NOT NULL,
  `report_code` varchar(5) NOT NULL DEFAULT '11011' COMMENT '11011: 사업보고서',
  `fs_div` varchar(3) NOT NULL DEFAULT 'CFS' COMMENT 'CFS: 연결, OFS: 별도',
  `collected_at` datetime NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`report_id`),
  UNIQUE KEY `uk_financial_report_receipt` (`receipt_no`,`fs_div`),
  KEY `idx_financial_report_company_year` (`stock_code`,`business_year`),
  CONSTRAINT `fk_financial_report_company` FOREIGN KEY (`stock_code`) REFERENCES `company` (`stock_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `financial_value` (
  `value_id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `report_id` bigint(20) unsigned NOT NULL,
  `account_code` varchar(50) NOT NULL COMMENT '프로젝트 공통 코드: REVENUE, OPERATING_PROFIT, NET_INCOME',
  `account_name` varchar(100) NOT NULL,
  `source_account_id` varchar(200) DEFAULT NULL COMMENT 'DART 원본 계정 ID',
  `source_account_name` varchar(200) DEFAULT NULL COMMENT 'DART 원본 계정명',
  `current_amount` decimal(30,2) DEFAULT NULL COMMENT '당기 금액, 누락은 NULL',
  `previous_amount` decimal(30,2) DEFAULT NULL COMMENT '전기 금액, 누락은 NULL',
  `currency` varchar(3) NOT NULL COMMENT 'KRW 등 원본 통화',
  `value_status` varchar(30) DEFAULT NULL COMMENT 'AVAILABLE, MISSING, AMBIGUOUS, UNSUPPORTED_CURRENCY',
  PRIMARY KEY (`value_id`),
  UNIQUE KEY `uk_financial_value_account` (`report_id`,`account_code`),
  CONSTRAINT `fk_financial_value_report` FOREIGN KEY (`report_id`) REFERENCES `financial_report` (`report_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
