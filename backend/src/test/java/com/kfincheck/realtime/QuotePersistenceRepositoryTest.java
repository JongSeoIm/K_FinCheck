package com.kfincheck.realtime;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class QuotePersistenceRepositoryTest {
    private JdbcTemplate jdbc;
    private QuotePersistenceRepository repository;

    @BeforeEach void setup() throws Exception {
        var dataSource = database();
        jdbc = new JdbcTemplate(dataSource);
        repository = new QuotePersistenceRepository(jdbc, new DataSourceTransactionManager(dataSource),
                JsonMapper.builder().addModule(new JavaTimeModule()).build());
    }

    static DriverManagerDataSource database() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE SCHEMA k_fincheck");
        jdbc.execute("CREATE TABLE company(stock_code varchar(6) PRIMARY KEY)");
        jdbc.update("INSERT INTO company VALUES ('005930')");
        String ddl = Files.readString(Path.of("../database/migrations/002_realtime_persistence_draft.sql"))
                .replaceAll("(?m)--[^\\n]*", "")
                .replace("USE k_fincheck;", "")
                .replaceAll("CHARACTER SET ascii COLLATE ascii_bin", "")
                .replaceAll("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci", "");
        for (String statement : ddl.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
        return dataSource;
    }

    @Test void restoresLatestQuoteAndUnfinishedCandlesIntoNewStore() {
        save(message(0, 70100, 100, 10), 0);
        save(message(1, 70300, 120, 20), 1);
        var last = message(2, 69900, 130, 10);
        save(last, 2);
        QuoteStore restarted = new QuoteStore();
        restarted.restorePersisted(repository.load("KIS_WEBSOCKET_KRX", List.of("005930")));
        var candle = bars(restarted, 60).getFirst();
        assertThat(candle.open()).isEqualTo(70100);
        assertThat(candle.high()).isEqualTo(70300);
        assertThat(candle.low()).isEqualTo(69900);
        assertThat(candle.close()).isEqualTo(69900);
        assertThat(candle.volume()).isEqualTo(40);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_candle WHERE is_closed=0", Long.class)).isEqualTo(7);
        assertThat(((Map<?, ?>) restarted.snapshot(60, List.of("005930")).get("quotes")).get("005930"))
                .isEqualTo(last.quote());
        // No persisted second candles: API still returns an empty list rather than throwing.
        assertThat(bars(restarted, 1)).isEmpty();
    }

    @Test void replayAfterCommitDoesNotAddVolumeAndRepairsAnEmptyProjection() {
        var event = message(0, 70100, 100, 10);
        save(event, 0);
        var result = save(event, 100);
        var store = new QuoteStore();
        store.applyPersisted(result);
        store.applyPersisted(result);
        assertThat(bars(store, 60).getFirst().volume()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_quote_event", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT SUM(trade_count) FROM stock_candle", Long.class)).isEqualTo(7);
    }

    @Test void staleOrReconnectedDuplicateDoesNotAdvanceQuoteOrDoubleCount() {
        var original = message(2, 70100, 100, 10);
        save(original, 0);
        save(message(2, 70100, 100, 10), 1); // Different event ID, same cumulative volume.
        save(message(1, 71000, 90, 10), 2); // Existing policy ignores late ticks.
        assertThat(jdbc.queryForObject("SELECT price FROM stock_quote_state", Long.class)).isEqualTo(70100);
        assertThat(jdbc.queryForObject("SELECT volume FROM stock_candle WHERE interval_seconds=60", Long.class)).isEqualTo(10);
    }

    @Test void serviceCanContinueSameCandleAfterRestoringMemory() {
        var properties = new StreamProperties();
        var firstStore = new QuoteStore();
        var service = new QuotePersistenceService(repository, firstStore, properties, "KIS_WEBSOCKET_KRX");
        var position = new QuotePersistenceRepository.Position("test-group", "test-quotes", 0, 0);
        service.consume(message(0, 70100, 100, 10), position);
        var nextStore = new QuoteStore();
        var restartedService = new QuotePersistenceService(repository, nextStore, properties, "KIS_WEBSOCKET_KRX");
        restartedService.restore();
        restartedService.consume(message(1, 70200, 120, 20),
                new QuotePersistenceRepository.Position("test-group", "test-quotes", 0, 1));
        assertThat(bars(nextStore, 60).getFirst().volume()).isEqualTo(30);
        assertThat(bars(nextStore, 60).getFirst().open()).isEqualTo(70100);
        assertThat(bars(nextStore, 60).getFirst().close()).isEqualTo(70200);
    }

    @Test void changingPayloadForExistingIdIsRejected() {
        var original = message(0, 70100, 100, 10);
        save(original, 0);
        var altered = new QuoteMessage(original.eventId(), message(1, 70200, 110, 10).quote());
        assertThatThrownBy(() -> save(altered, 1)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT price FROM stock_quote_state", Long.class)).isEqualTo(70100);
    }

    @Test void failureAfterCandleUpdatesRollsBackLedgerAndAllIntervals() {
        save(message(0, 70100, 100, 10), 0);
        jdbc.execute("ALTER TABLE stock_quote_state ADD CONSTRAINT test_reject_price CHECK (price < 80000)");
        var rejected = message(1, 80000, 120, 20);
        assertThatThrownBy(() -> save(rejected, 1)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_quote_event", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT SUM(volume) FROM stock_candle", Long.class)).isEqualTo(70);
        jdbc.execute("ALTER TABLE stock_quote_state DROP CONSTRAINT test_reject_price");
        save(rejected, 1);
        assertThat(jdbc.queryForObject("SELECT volume FROM stock_candle WHERE interval_seconds=60", Long.class)).isEqualTo(30);
    }

    @Test void nextDayResetsCumulativeComparisonAndClosesOldWindows() {
        save(message(0, 70100, 100, 10), 0);
        var t = Instant.parse("2026-11-06T00:00:00Z");
        var next = new QuoteEvent("005930", 70200, 100, .14, 70200, 70200, 70200, 5, 5,
                "2026-11-06", "09:00", t, t, "KIS_WEBSOCKET_KRX");
        save(QuoteMessage.create(next), 1);
        assertThat(jdbc.queryForObject("SELECT cumulative_volume FROM stock_quote_state", Long.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_candle WHERE is_closed=1", Long.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_candle", Long.class)).isEqualTo(14);
    }

    @Test void unrelatedCompanyFailureLeavesNoPartiallyStoredEvents() {
        var q = message(0, 70100, 100, 10).quote();
        var unsupported = new QuoteEvent("000660", q.price(), 0, 0, q.open(), q.high(), q.low(), q.volume(), q.tradeVolume(),
                q.businessDate(), q.tradeTime(), q.exchangeTime(), q.receivedAt(), q.source());
        assertThatThrownBy(() -> save(QuoteMessage.create(unsupported), 0)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_quote_event", Long.class)).isZero();
    }

    private QuotePersistenceRepository.Projection save(QuoteMessage event, long offset) {
        return repository.save(event, new QuotePersistenceRepository.Position("test-group", "test-quotes", 0, offset));
    }
    static QuoteMessage message(int seconds, long price, long cumulative, long volume) {
        Instant t = Instant.parse("2026-11-05T05:30:00Z").plusSeconds(seconds);
        return QuoteMessage.create(new QuoteEvent("005930", price, 100, .14, 70000, 71000, 69000, cumulative, volume,
                "2026-11-05", "14:30:" + String.format("%02d", seconds), t, t, "KIS_WEBSOCKET_KRX"));
    }
    @SuppressWarnings("unchecked") private static List<QuoteStore.ObservedCandle> bars(QuoteStore store, int interval) {
        return ((Map<String, List<QuoteStore.ObservedCandle>>) store.snapshot(interval, List.of("005930")).get("candles")).get("005930");
    }
}
