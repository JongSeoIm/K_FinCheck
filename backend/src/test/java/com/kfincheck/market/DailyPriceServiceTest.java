package com.kfincheck.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DailyPriceServiceTest {
    private JdbcTemplate jdbc;
    private final List<String> kisCalls = new ArrayList<>();
    private final List<DailyPriceService.DailyPrice> kisRows = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        jdbc = database("src/main/resources/db/daily-price.sql");
    }

    static JdbcTemplate database(String script) throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        String ddl = Files.readString(Path.of(script))
                .replaceAll("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci", "");
        for (String statement : ddl.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
        return jdbc;
    }

    private DailyPriceService service(LocalDateTime now) {
        Clock clock = Clock.fixed(now.atZone(DailyPriceService.KST).toInstant(), DailyPriceService.KST);
        return new DailyPriceService(jdbc, (code, from, to) -> {
            kisCalls.add(code + ":" + from + "~" + to);
            return kisRows.stream().filter(row -> !row.tradeDate().isBefore(from) && !row.tradeDate().isAfter(to)).toList();
        }, clock);
    }

    private static DailyPriceService.DailyPrice day(String date, long close) {
        return new DailyPriceService.DailyPrice("005930", LocalDate.parse(date), close - 100, close + 500, close - 900, close, 1000L);
    }

    @Test
    void missingDayIsFetchedOnceThenReadFromDb() {
        kisRows.addAll(List.of(day("2026-11-05", 50000), day("2026-11-06", 51000)));
        var service = service(LocalDateTime.of(2026, 11, 10, 10, 0));

        var first = service.close("005930", LocalDate.of(2026, 11, 6));
        assertThat(first.close()).isEqualTo(51000);
        assertThat(first.previousClose()).isEqualTo(50000);
        assertThat(first.source()).isEqualTo("KIS→DB");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_daily_price", Integer.class)).isEqualTo(2);

        var second = service.close("005930", LocalDate.of(2026, 11, 6));
        assertThat(second.source()).isEqualTo("DB");
        assertThat(second.close()).isEqualTo(51000);
        assertThat(kisCalls).hasSize(1);
    }

    @Test
    void dayWithoutTradingFallsBackToPreviousTradingDay() {
        // 11/9(월)를 휴장일 목록에 없는 임시공휴일이라고 가정한다. KIS 에도 그날 일봉이 없다.
        kisRows.addAll(List.of(day("2026-11-05", 50000), day("2026-11-06", 51000)));
        var service = service(LocalDateTime.of(2026, 11, 10, 10, 0));
        var result = service.close("005930", LocalDate.of(2026, 11, 9));
        assertThat(result.tradeDate()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(result.close()).isEqualTo(51000);
        assertThat(result.previousClose()).isEqualTo(50000);
        assertThat(result.source()).isEqualTo("직전 거래일");
    }

    @Test
    void dayWithoutTradingIsNotInventedWhenNothingNearby() {
        kisRows.add(day("2026-10-20", 49000));  // 7일보다 앞의 일봉은 쓰지 않는다
        var service = service(LocalDateTime.of(2026, 11, 10, 10, 0));
        service.collect("005930", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 9));  // DB 에는 10/20 이 있다
        assertThatThrownBy(() -> service.close("005930", LocalDate.of(2026, 11, 9)))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void todayIsStoredOnlyAfterClose() {
        kisRows.add(day("2026-11-10", 52000));
        var duringMarket = service(LocalDateTime.of(2026, 11, 10, 14, 0));
        assertThat(duringMarket.collect("005930", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 10))).isZero();
        assertThatThrownBy(() -> duringMarket.close("005930", LocalDate.of(2026, 11, 10)))
                .isInstanceOf(IllegalArgumentException.class);

        var afterClose = service(LocalDateTime.of(2026, 11, 10, 15, 45));
        assertThat(afterClose.collect("005930", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 10))).isEqualTo(1);
    }

    @Test
    void collectSplitsLongRangeAndOverwrites() {
        kisRows.add(day("2026-11-06", 51000));
        var service = service(LocalDateTime.of(2026, 11, 10, 18, 0));
        service.collect("005930", LocalDate.of(2026, 6, 23), LocalDate.of(2026, 11, 10));
        assertThat(kisCalls).hasSize(3);
        kisRows.set(0, day("2026-11-06", 51500));
        service.collect("005930", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 10));
        assertThat(jdbc.queryForObject("SELECT close_price FROM stock_daily_price WHERE trade_date = '2026-11-06'", Long.class))
                .isEqualTo(51500);
    }

    @Test
    void rejectsFutureAndBadCode() {
        var service = service(LocalDateTime.of(2026, 11, 10, 10, 0));
        assertThatThrownBy(() -> service.close("005930", LocalDate.of(2026, 11, 11))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.close("5930", LocalDate.of(2026, 11, 6))).isInstanceOf(IllegalArgumentException.class);
        assertThat(kisCalls).isEmpty();
    }
}
