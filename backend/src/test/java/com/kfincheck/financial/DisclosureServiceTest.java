package com.kfincheck.financial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DisclosureServiceTest {
    private JdbcTemplate jdbc;
    private final List<String> dartCalls = new ArrayList<>();
    private final List<DisclosureService.Filing> filings = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE company(stock_code varchar(6) PRIMARY KEY, company_name varchar(100))");
        jdbc.update("INSERT INTO company VALUES ('005930', '삼성전자')");
        String ddl = Files.readString(Path.of("src/main/resources/db/disclosure.sql"))
                .replaceAll("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci", "");
        for (String statement : ddl.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
    }

    private DisclosureService service(LocalDateTime now) {
        Clock clock = Clock.fixed(now.atZone(DisclosureService.KST).toInstant(), DisclosureService.KST);
        return new DisclosureService(jdbc, stock -> "00126380", (corp, from, to) -> {
            dartCalls.add(from + "~" + to);
            return filings.stream().filter(f -> !f.receivedDate().isBefore(from) && !f.receivedDate().isAfter(to)).toList();
        }, clock);
    }

    private static DisclosureService.Filing filing(String receipt, String date, String title) {
        return new DisclosureService.Filing(receipt, "삼성전자", title, "삼성전자", LocalDate.parse(date), "유");
    }

    @Test
    void pastDayIsFetchedOnceThenReadFromDb() {
        filings.add(filing("20261106000111", "2026-11-06", "현금ㆍ현물배당결정"));
        filings.add(filing("20261105000222", "2026-11-05", "기업설명회(IR)개최"));
        var service = service(LocalDateTime.of(2026, 11, 10, 10, 0));

        var result = service.list("005930", LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 6));
        assertThat(result.items()).extracting(DisclosureService.Disclosure::reportName).containsExactly("현금ㆍ현물배당결정");
        assertThat(result.items().getFirst().sourceUrl()).endsWith("rcpNo=20261106000111");
        assertThat(result.companyName()).isEqualTo("삼성전자");

        service.list("005930", LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 6));
        assertThat(dartCalls).hasSize(1);
    }

    @Test
    void onlyMissingDaysAreFetched() {
        filings.add(filing("20261106000111", "2026-11-06", "A"));
        filings.add(filing("20261103000333", "2026-11-03", "B"));
        var service = service(LocalDateTime.of(2026, 11, 10, 10, 0));
        service.list("005930", LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 6));
        var week = service.list("005930", LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 6));
        assertThat(dartCalls).containsExactly("2026-11-06~2026-11-06", "2026-11-02~2026-11-06");
        assertThat(week.items()).extracting(DisclosureService.Disclosure::receiptNo)
                .containsExactly("20261106000111", "20261103000333");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM disclosure", Integer.class)).isEqualTo(2);
    }

    @Test
    void todayIsRefreshedAfterTenMinutes() {
        var service = service(LocalDateTime.of(2026, 11, 10, 10, 0));
        service.list("005930", LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 10));
        service.list("005930", LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 10));
        assertThat(dartCalls).hasSize(1);

        filings.add(filing("20261110000999", "2026-11-10", "새 공시"));
        var later = service(LocalDateTime.of(2026, 11, 10, 10, 11));
        var result = later.list("005930", LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 10));
        assertThat(dartCalls).hasSize(2);
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void dayFetchedDuringThatDayIsFetchedAgainLater() {
        var sameDay = LocalDateTime.of(2026, 11, 10, 17, 0);
        assertThat(DisclosureService.complete(LocalDate.of(2026, 11, 10), sameDay, LocalDateTime.of(2026, 11, 11, 9, 0))).isFalse();
        assertThat(DisclosureService.complete(LocalDate.of(2026, 11, 10), LocalDateTime.of(2026, 11, 11, 0, 5),
                LocalDateTime.of(2026, 12, 1, 9, 0))).isTrue();
    }

    @Test
    void rejectsInvalidRanges() {
        var service = service(LocalDateTime.of(2026, 11, 10, 10, 0));
        assertThatThrownBy(() -> service.list("005930", LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 11)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list("005930", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 11, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list("005930", LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(dartCalls).isEmpty();
    }
}
