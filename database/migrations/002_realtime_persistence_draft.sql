-- 실시간 분봉 저장·복원 설계 초안. 실제 DB 적용과 서버 자동 실행 등록은 하지 않는다.
-- 기존 schema.sql의 company 테이블이 준비되어 있어야 한다.
-- 기존 회원·실적·대화 테이블은 변경하지 않는다.
-- 모든 *_at 시각은 UTC로 저장한다. business_date는 거래소의 한국시간 거래일이다.
-- 가격 단위는 원(KRW), 거래량 단위는 주이며 기존 QuoteEvent의 정수 가격을 기준으로 한다.
-- source는 KIS_WEBSOCKET_KRX 등 수집 경로를 식별한다. 다른 시장 데이터는 섞지 않는다.
USE k_fincheck;

-- 저장 대상: 화면에서 제공하는 1·3·5·10·15·30·60분봉.
-- 진행 중인 봉도 매 처리 트랜잭션에 반영한다. 1·5·30초봉은 이 초안에서 제외한다.
-- 이 테이블에는 수신하여 반영한 체결만 집계된다. 수집 중단 구간을 자동 복구하지 않는다.
CREATE TABLE IF NOT EXISTS `stock_candle` (
  `source` varchar(40) NOT NULL COMMENT '수집 경로·시장',
  `stock_code` varchar(6) NOT NULL,
  `interval_seconds` smallint unsigned NOT NULL COMMENT '60,180,300,600,900,1800,3600',
  `bucket_start_at` datetime(6) NOT NULL COMMENT 'UTC 봉 시작 시각, 구간은 시작 이상·끝 미만',
  `business_date` date NOT NULL COMMENT '거래소 기준 거래일',
  `open_price` bigint unsigned NOT NULL,
  `high_price` bigint unsigned NOT NULL,
  `low_price` bigint unsigned NOT NULL,
  `close_price` bigint unsigned NOT NULL,
  `volume` bigint unsigned NOT NULL COMMENT '중복 제거 후 반영한 개별 체결량의 합',
  `trade_count` bigint unsigned NOT NULL COMMENT '중복 제거 후 반영한 체결 건수',
  `first_exchange_at` datetime(6) NOT NULL COMMENT '시가를 결정한 체결 시각 UTC',
  `last_exchange_at` datetime(6) NOT NULL COMMENT '종가를 결정한 체결 시각 UTC',
  `first_cumulative_volume` bigint unsigned NOT NULL COMMENT '동일 초 체결의 순서 판단 보조값',
  `last_cumulative_volume` bigint unsigned NOT NULL COMMENT '동일 초 체결의 순서 판단 보조값',
  `is_closed` tinyint(1) NOT NULL DEFAULT 0 COMMENT '시간 구간 종료 여부. 누락 없음의 보장은 아님',
  `updated_at` datetime(6) NOT NULL COMMENT '마지막 DB 반영 시각 UTC, 애플리케이션에서 지정',
  PRIMARY KEY (`source`,`stock_code`,`interval_seconds`,`bucket_start_at`),
  KEY `idx_stock_candle_business_date` (`business_date`),
  CONSTRAINT `fk_stock_candle_company` FOREIGN KEY (`stock_code`) REFERENCES `company` (`stock_code`),
  CONSTRAINT `ck_stock_candle_interval` CHECK (`interval_seconds` IN (60,180,300,600,900,1800,3600)),
  CONSTRAINT `ck_stock_candle_prices` CHECK (
    `low_price` > 0 AND `high_price` >= `low_price`
    AND `open_price` BETWEEN `low_price` AND `high_price`
    AND `close_price` BETWEEN `low_price` AND `high_price`
  ),
  CONSTRAINT `ck_stock_candle_count` CHECK (`trade_count` > 0),
  CONSTRAINT `ck_stock_candle_closed` CHECK (`is_closed` IN (0,1)),
  CONSTRAINT `ck_stock_candle_times` CHECK (
    `first_exchange_at` >= `bucket_start_at`
    AND `last_exchange_at` >= `first_exchange_at`
    AND `last_exchange_at` < TIMESTAMPADD(SECOND, `interval_seconds`, `bucket_start_at`)
  ),
  CONSTRAINT `ck_stock_candle_order` CHECK (`last_cumulative_volume` >= `first_cumulative_volume`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 마지막으로 DB에 확정한 시세 상태를 복원하는 테이블이다.
-- 재시작 후 exchange_at / received_at을 확인하고 오래된 값을 실시간으로 표시하지 않는다.
-- ※ 아래 상태는 마지막 시세를 복원하는 용도이며 Kafka 소비 위치의 대체물이 아니다.
CREATE TABLE IF NOT EXISTS `stock_quote_state` (
  `source` varchar(40) NOT NULL,
  `stock_code` varchar(6) NOT NULL,
  `last_event_id` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '마지막 시세에 반영한 프로젝트 이벤트 UUID',
  `business_date` date NOT NULL,
  `trade_time` varchar(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'QuoteEvent의 한국시간 HH:mm 또는 HH:mm:ss',
  `exchange_at` datetime(6) NOT NULL COMMENT '마지막 시세 체결 시각 UTC',
  `received_at` datetime(6) NOT NULL COMMENT '수집기 최초 수신 시각 UTC, 재전송 시 유지',
  `price` bigint unsigned NOT NULL,
  `price_change` bigint NOT NULL COMMENT '전일 대비 가격 차이, 음수 허용',
  `change_rate` decimal(12,6) NOT NULL COMMENT '전일 대비 등락률, 퍼센트 단위',
  `day_open` bigint unsigned NOT NULL,
  `day_high` bigint unsigned NOT NULL,
  `day_low` bigint unsigned NOT NULL,
  `cumulative_volume` bigint unsigned NOT NULL COMMENT '해당 거래일 누적 거래량',
  `trade_volume` bigint unsigned NOT NULL COMMENT '마지막 체결의 개별 거래량',
  `updated_at` datetime(6) NOT NULL COMMENT 'DB 반영 시각 UTC',
  PRIMARY KEY (`source`,`stock_code`),
  CONSTRAINT `fk_stock_quote_state_company` FOREIGN KEY (`stock_code`) REFERENCES `company` (`stock_code`),
  CONSTRAINT `ck_stock_quote_state_price` CHECK (`price` > 0),
  CONSTRAINT `ck_stock_quote_state_trade_time` CHECK (`trade_time` REGEXP '^[0-9]{2}:[0-9]{2}(:[0-9]{2})?$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 원본 체결 보관 테이블이 아니라 처리 완료 이벤트의 중복 판정 장부이다.
-- event_id는 수집기 최초 영속 기록 시 발급하고 Kafka 재전송에도 같은 값을 유지해야 한다.
-- received_at이나 Kafka offset으로 event_id를 새로 만들지 않는다.
-- 이 ID는 내부 재전송 중복을 식별한다. KIS 재접속으로 다시 받은 체결의 중복 식별은 별도 검증 대상이다.
CREATE TABLE IF NOT EXISTS `processed_quote_event` (
  `consumer_group` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `event_id` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `source` varchar(40) NOT NULL,
  `stock_code` varchar(6) NOT NULL,
  `business_date` date NOT NULL,
  `exchange_at` datetime(6) NOT NULL COMMENT '체결 시각 UTC',
  `payload_hash` binary(32) NOT NULL COMMENT '정규화한 불변 이벤트 내용의 SHA-256, 동일 ID의 내용 변경 검사',
  `kafka_topic` varchar(249) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `kafka_partition` int unsigned NOT NULL,
  `kafka_offset` bigint unsigned NOT NULL COMMENT '최초 처리 레코드 위치, 별도 위치 커밋을 대체하지 않음',
  `processed_at` datetime(6) NOT NULL COMMENT 'DB 반영 시각 UTC',
  PRIMARY KEY (`consumer_group`,`event_id`),
  UNIQUE KEY `uk_processed_quote_position` (`consumer_group`,`kafka_topic`,`kafka_partition`,`kafka_offset`),
  KEY `idx_processed_quote_business_date` (`business_date`),
  KEY `idx_processed_quote_stock` (`source`,`stock_code`,`exchange_at`),
  CONSTRAINT `fk_processed_quote_company` FOREIGN KEY (`stock_code`) REFERENCES `company` (`stock_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 구현 시 함께 지켜야 할 규칙 (테이블 생성만으로 보장되지 않는다):
-- 1. 종목별 집계를 직렬화하고 중복 장부 INSERT, 모든 주기의 봉 갱신, 최신 시세 갱신을
--    하나의 DB 트랜잭션으로 처리한다. 일부 실패 시 모두 롤백한다.
-- 2. 동일 event_id와 동일 내용은 재집계하지 않는다. 다른 내용이면 오류로 처리한다.
--    INSERT IGNORE로 모든 제약 오류를 정상 중복으로 숨기지 않는다.
-- 3. DB 커밋 성공 후 Kafka 처리 위치를 커밋한다. Redis 갱신은 이후 수행한다.
--    장부에 저장된 offset의 MAX+1로 소비 위치를 임의 이동하지 않는다.
-- 4. 같은 시각의 체결은 누적 거래량 등 순서 기준으로 시가·종가를 결정한다.
--    늦게 도착한 체결이 최신 시세를 과거로 되돌리지 않도록 한다.
-- 5. 거래일 변경 시 누적 거래량 기준을 분리한다. 누적량 차이를 무조건 봉 거래량으로 넣지 않는다.
-- 6. 수집 중단 구간과 KIS 재접속 중복은 외부 자료·원본 필드로 별도 검증한다.
-- 7. 장부 보존 기간은 Kafka 재처리·수집기 재전송 가능 기간을 함께 고려해 결정한다.
--    기간 확정 전 자동 삭제 작업을 추가하지 않는다.
-- 8. QuoteStore 복원과 Redis 구성은 별도 구현한다. 재시작·중복·지연·장애 테스트가 필요하다.
-- 미포함: 원본 체결 전문, 수집기 전송 대기 저장소, Redis, Kafka 볼륨·보존 설정.
