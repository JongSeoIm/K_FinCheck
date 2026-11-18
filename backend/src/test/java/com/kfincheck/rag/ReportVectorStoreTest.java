package com.kfincheck.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** H2 는 VECTOR 를 지원하지 않으므로 "MariaDB 저장 + Java 계산" 경로를 검사한다. */
class ReportVectorStoreTest {
    private static final String RECEIPT = "20260310000123";
    private JdbcTemplate jdbc;
    private ReportVectorStore store;

    @BeforeEach
    void setup() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        store = new ReportVectorStore(jdbc, new ObjectMapper());
        store.createTables();
    }

    private static ReportVectorStore.SaveRequest request(List<ReportVectorStore.Chunk> chunks) {
        return new ReportVectorStore.SaveRequest("000660", "사업보고서 (2025.12)", 2025,
                "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + RECEIPT, "text-embedding-3-small", chunks);
    }

    @Test
    void savesChunksAndFindsNearestBySimilarity() {
        store.save(RECEIPT, request(List.of(
                new ReportVectorStore.Chunk("사업의 내용", "DRAM 과 NAND 를 생산합니다.", new double[]{1, 0, 0}),
                new ReportVectorStore.Chunk("위험", "미국 수출 규제 위험이 있습니다.", new double[]{0, 1, 0}),
                new ReportVectorStore.Chunk("배당", "배당 정책", new double[]{0, 0.2, 1}))));

        var info = store.info(RECEIPT).orElseThrow();
        assertThat(info.chunkCount()).isEqualTo(3);
        assertThat(info.embeddingModel()).isEqualTo("text-embedding-3-small");

        var result = store.search(RECEIPT, new double[]{0, 1, 0.1}, 2);
        assertThat(result.searchMode()).isEqualTo("MariaDB 저장 + Java 계산");
        assertThat(result.hits()).extracting(ReportVectorStore.Hit::section).containsExactly("위험", "배당");
        assertThat(result.hits().getFirst().score()).isGreaterThan(0.9);
    }

    @Test
    void savingAgainReplacesOldChunks() {
        store.save(RECEIPT, request(List.of(new ReportVectorStore.Chunk("A", "old", new double[]{1, 0}))));
        store.save(RECEIPT, request(List.of(new ReportVectorStore.Chunk("B", "new1", new double[]{1, 0}),
                new ReportVectorStore.Chunk("C", "new2", null))));
        assertThat(store.texts(RECEIPT)).extracting(ReportVectorStore.Text::content).containsExactly("new1", "new2");
        assertThat(store.search(RECEIPT, new double[]{1, 0}, 5).hits()).hasSize(1);  // 벡터 없는 조각은 벡터 검색에서 빠짐
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM report_index", Integer.class)).isEqualTo(1);
    }

    @Test
    void unknownReportAndBadInputAreRejected() {
        assertThat(store.info(RECEIPT)).isEmpty();
        assertThatThrownBy(() -> store.info("123")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save(RECEIPT, request(List.of()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.search(RECEIPT, new double[0], 5)).isInstanceOf(IllegalArgumentException.class);
    }
}
