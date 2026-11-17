package com.kfincheck.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 사업보고서 RAG 조각·벡터를 MariaDB 에 저장하고 SQL 로 검색한다.
 * <ul>
 *   <li>report_index : 보고서 1건(접수번호)마다 한 줄. 색인한 임베딩 모델·조각 수</li>
 *   <li>report_chunk : 조각 본문과 벡터. 벡터는 embedding_json(모든 버전)에 항상 저장하고,
 *       MariaDB 11.7 이상이면 embedding(VECTOR 1536) 컬럼에도 넣어 SQL 로 거리 계산까지 DB 가 한다.</li>
 * </ul>
 * MariaDB 가 11.7 보다 낮으면(VECTOR 미지원) 저장은 그대로 하고 거리 계산만 Java 에서 한다.
 * 나중에 MariaDB 를 올리면 서버 시작 때 embedding_json 으로 VECTOR 컬럼을 채운다.
 */
@Service
public class ReportVectorStore {
    private static final Logger log = LoggerFactory.getLogger(ReportVectorStore.class);
    static final int DIMENSIONS = 1536;
    static final int MAX_K = 10;

    public record Chunk(String section, String content, double[] embedding) { }

    public record SaveRequest(String stockCode, String reportName, Integer businessYear, String sourceUrl,
                              String embeddingModel, List<Chunk> chunks) { }

    public record ReportInfo(String receiptNo, String stockCode, String embeddingModel, int chunkCount, String searchMode) { }

    public record Hit(String section, String content, double score) { }

    public record SearchResult(String searchMode, List<Hit> hits) { }

    public record Text(String section, String content) { }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private volatile boolean vectorSupported;

    public ReportVectorStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @PostConstruct
    void initialize() {
        try {
            createTables();
        } catch (DataAccessException error) {
            log.warn("사업보고서 RAG 테이블을 준비하지 못했습니다. DB 설정을 확인하세요: {}", error.getMessage());
        }
    }

    void createTables() {
        jdbc.execute("""
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
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS report_chunk (
                 receipt_no VARCHAR(14) NOT NULL,
                 chunk_index INT NOT NULL,
                 section VARCHAR(500) NOT NULL,
                 content TEXT NOT NULL,
                 embedding_json MEDIUMTEXT NULL,
                 PRIMARY KEY (receipt_no, chunk_index)
                )""");
        vectorSupported = detectVector();
        if (vectorSupported) {
            jdbc.execute("ALTER TABLE report_chunk ADD COLUMN IF NOT EXISTS embedding VECTOR(" + DIMENSIONS + ") NULL");
            int filled = jdbc.update("""
                    UPDATE report_chunk SET embedding = VEC_FromText(embedding_json)
                    WHERE embedding IS NULL AND embedding_json IS NOT NULL
                      AND JSON_LENGTH(embedding_json) = ?""", DIMENSIONS);
            if (filled > 0) log.info("사업보고서 조각 {}개의 VECTOR 컬럼을 채웠습니다.", filled);
        }
        log.info("사업보고서 RAG 저장소: MariaDB, 검색 방식 = {}", searchMode());
    }

    /** MariaDB 11.7 이상이면 VEC_DISTANCE_COSINE 이 동작한다. */
    private boolean detectVector() {
        try {
            jdbc.queryForObject("SELECT VEC_DISTANCE_COSINE(VEC_FromText('[1,0]'), VEC_FromText('[1,0]'))", Double.class);
            return true;
        } catch (DataAccessException unsupported) {
            log.warn("이 MariaDB 는 VECTOR 를 지원하지 않습니다(11.7 이상 필요). 벡터는 저장하고 거리 계산만 Java 에서 합니다.");
            return false;
        }
    }

    String searchMode() {
        return vectorSupported ? "MariaDB VECTOR" : "MariaDB 저장 + Java 계산";
    }

    public Optional<ReportInfo> info(String receiptNo) {
        validateReceipt(receiptNo);
        List<ReportInfo> rows = jdbc.query("SELECT receipt_no, stock_code, embedding_model, chunk_count FROM report_index WHERE receipt_no = ?",
                (rs, index) -> new ReportInfo(rs.getString("receipt_no"), rs.getString("stock_code"),
                        rs.getString("embedding_model"), rs.getInt("chunk_count"), searchMode()), receiptNo);
        return rows.stream().findFirst();
    }

    /** 보고서 1건의 조각을 통째로 바꾼다. */
    @Transactional
    public void save(String receiptNo, SaveRequest request) {
        validateReceipt(receiptNo);
        if (request == null || request.stockCode() == null || !request.stockCode().matches("\\d{6}")) {
            throw new IllegalArgumentException("종목 코드는 숫자 6자리여야 합니다.");
        }
        if (request.embeddingModel() == null || request.embeddingModel().isBlank() || request.embeddingModel().length() > 60) {
            throw new IllegalArgumentException("임베딩 모델 이름이 필요합니다.");
        }
        List<Chunk> chunks = request.chunks() == null ? List.of() : request.chunks();
        if (chunks.isEmpty() || chunks.size() > 2000) throw new IllegalArgumentException("조각은 1~2000개여야 합니다.");

        jdbc.update("DELETE FROM report_chunk WHERE receipt_no = ?", receiptNo);
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            if (chunk == null || chunk.content() == null || chunk.content().isBlank()) {
                throw new IllegalArgumentException("비어 있는 조각이 있습니다.");
            }
            String section = chunk.section() == null ? "" : cut(chunk.section(), 500);
            rows.add(new Object[]{receiptNo, i, section, chunk.content(), vectorText(chunk.embedding())});
        }
        jdbc.batchUpdate("INSERT INTO report_chunk (receipt_no, chunk_index, section, content, embedding_json) VALUES (?, ?, ?, ?, ?)", rows);
        if (vectorSupported) {
            jdbc.update("UPDATE report_chunk SET embedding = VEC_FromText(embedding_json) WHERE receipt_no = ? AND embedding_json IS NOT NULL"
                    + " AND JSON_LENGTH(embedding_json) = ?", receiptNo, DIMENSIONS);
        }
        jdbc.update("""
                INSERT INTO report_index (receipt_no, stock_code, report_name, business_year, source_url, embedding_model, chunk_count, indexed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE stock_code = VALUES(stock_code), report_name = VALUES(report_name),
                    business_year = VALUES(business_year), source_url = VALUES(source_url),
                    embedding_model = VALUES(embedding_model), chunk_count = VALUES(chunk_count), indexed_at = VALUES(indexed_at)
                """, receiptNo, request.stockCode(), cutOrNull(request.reportName(), 300), request.businessYear(),
                cutOrNull(request.sourceUrl(), 300), request.embeddingModel(), chunks.size(), Timestamp.valueOf(LocalDateTime.now()));
    }

    /** 질문 벡터와 가까운 조각 k개. MariaDB 11.7 이상이면 SQL 의 VEC_DISTANCE_COSINE 으로 DB 가 정렬한다. */
    public SearchResult search(String receiptNo, double[] query, int k) {
        validateReceipt(receiptNo);
        if (query == null || query.length == 0) throw new IllegalArgumentException("질문 벡터가 필요합니다.");
        int limit = Math.max(1, Math.min(k, MAX_K));
        if (vectorSupported && query.length == DIMENSIONS) {
            List<Hit> hits = jdbc.query("""
                    SELECT section, content, VEC_DISTANCE_COSINE(embedding, VEC_FromText(?)) AS distance
                    FROM report_chunk
                    WHERE receipt_no = ? AND embedding IS NOT NULL
                    ORDER BY distance
                    LIMIT ?
                    """, (rs, index) -> new Hit(rs.getString("section"), rs.getString("content"), round(1 - rs.getDouble("distance"))),
                    vectorText(query), receiptNo, limit);
            return new SearchResult(searchMode(), hits);
        }
        List<Hit> scored = new ArrayList<>();
        jdbc.query("SELECT section, content, embedding_json FROM report_chunk WHERE receipt_no = ? AND embedding_json IS NOT NULL",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    double[] vector = parse(rs.getString("embedding_json"));
                    if (vector.length == query.length) {
                        scored.add(new Hit(rs.getString("section"), rs.getString("content"), round(cosine(query, vector))));
                    }
                }, receiptNo);
        scored.sort(Comparator.comparingDouble(Hit::score).reversed());
        return new SearchResult("MariaDB 저장 + Java 계산", List.copyOf(scored.subList(0, Math.min(limit, scored.size()))));
    }

    /** 임베딩 없이 글자 비교로 찾을 때 쓰는 조각 본문 목록. */
    public List<Text> texts(String receiptNo) {
        validateReceipt(receiptNo);
        return jdbc.query("SELECT section, content FROM report_chunk WHERE receipt_no = ? ORDER BY chunk_index",
                (rs, index) -> new Text(rs.getString("section"), rs.getString("content")), receiptNo);
    }

    private String vectorText(double[] vector) {
        if (vector == null || vector.length == 0) return null;
        try {
            return json.writeValueAsString(vector);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("벡터를 저장할 수 없습니다.");
        }
    }

    private double[] parse(String text) {
        try {
            return json.readValue(text, double[].class);
        } catch (JsonProcessingException error) {
            return new double[0];
        }
    }

    static double cosine(double[] a, double[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return normA == 0 || normB == 0 ? 0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private static double round(double value) {
        return Math.round(value * 10000) / 10000.0;
    }

    private static void validateReceipt(String receiptNo) {
        if (receiptNo == null || !receiptNo.matches("\\d{14}")) throw new IllegalArgumentException("접수번호는 숫자 14자리여야 합니다.");
    }

    private static String cut(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static String cutOrNull(String value, int max) {
        return value == null ? null : cut(value, max);
    }
}
