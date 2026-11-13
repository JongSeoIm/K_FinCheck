-- 사업보고서 RAG 조각·벡터 (Spring ReportVectorStore 가 시작 시 자동 생성하므로 수동 실행은 선택)
-- embedding(VECTOR) 컬럼은 MariaDB 11.7 이상에서만 추가된다. 낮은 버전은 embedding_json 만 쓰고 거리 계산은 Java 에서 한다.
USE k_fincheck;

CREATE TABLE IF NOT EXISTS report_index (
 receipt_no VARCHAR(14) NOT NULL,
 stock_code VARCHAR(6) NOT NULL,
 report_name VARCHAR(300) NULL,
 business_year INT NULL,
 source_url VARCHAR(300) NULL,
 embedding_model VARCHAR(60) NOT NULL,
 chunk_count INT NOT NULL,
 indexed_at DATETIME NOT NULL,
 PRIMARY KEY (receipt_no)
);

CREATE TABLE IF NOT EXISTS report_chunk (
 receipt_no VARCHAR(14) NOT NULL,
 chunk_index INT NOT NULL,
 section VARCHAR(500) NOT NULL,
 content TEXT NOT NULL,
 embedding_json MEDIUMTEXT NULL,
 PRIMARY KEY (receipt_no, chunk_index)
);

-- MariaDB 11.7 이상
ALTER TABLE report_chunk ADD COLUMN IF NOT EXISTS embedding VECTOR(1536) NULL;
