package com.kfincheck.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

@Repository
@ConditionalOnProperty(name = "stream.persistence.enabled", havingValue = "true")
public class QuotePersistenceRepository {
    public record Position(String group, String topic, int partition, long offset) { }
    public record Projection(QuoteEvent quote, List<DurableCandle> candles) { }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    public QuotePersistenceRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper mapper) {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager); this.mapper = mapper;
    }

    public Projection save(QuoteMessage message, Position position) {
        return transactions.execute(status -> {
            QuoteEvent q = message.quote();
            validate(q);
            // One Kafka partition / one consumer is currently configured. Row locks also serialize existing state.
            var states = jdbc.query("SELECT * FROM stock_quote_state WHERE source=? AND stock_code=? FOR UPDATE",
                    QUOTE_ROW, q.source(), q.stockCode());
            byte[] hash = hash(q);
            var seen = jdbc.queryForList("SELECT payload_hash FROM processed_quote_event WHERE consumer_group=? AND event_id=?",
                    position.group(), message.eventId());
            if (!seen.isEmpty()) {
                if (!MessageDigest.isEqual((byte[]) seen.getFirst().get("payload_hash"), hash))
                    throw new IllegalStateException("같은 시세 이벤트 ID의 내용이 변경되었습니다.");
                return projection(q, states.isEmpty() ? null : states.getFirst());
            }
            Instant now = Instant.now();
            jdbc.update("""
                    INSERT INTO processed_quote_event
                    (consumer_group,event_id,source,stock_code,business_date,exchange_at,payload_hash,
                     kafka_topic,kafka_partition,kafka_offset,processed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """, position.group(), message.eventId(), q.source(), q.stockCode(), LocalDate.parse(q.businessDate()),
                    utc(q.exchangeTime()), hash, position.topic(), position.partition(), position.offset(), utc(now));
            QuoteEvent old = states.isEmpty() ? null : states.getFirst();
            // Preserve the existing ordered-stream policy. Late ticks are recorded, but not added retroactively.
            if (!QuoteStore.isNewer(q, old)) return projection(q, old);
            List<DurableCandle> changed = new ArrayList<>();
            for (int interval : DurableCandle.INTERVALS) {
                var rows = candles(q, interval);
                DurableCandle candle = rows.isEmpty() ? DurableCandle.first(q, interval) : rows.getFirst().append(q);
                write(candle, now); changed.add(candle);
            }
            writeState(q, message.eventId(), now);
            jdbc.update("""
                    UPDATE stock_candle SET is_closed=1,updated_at=?
                    WHERE source=? AND stock_code=? AND is_closed=0
                      AND TIMESTAMPADD(SECOND,interval_seconds,bucket_start_at)<=?
                    """, utc(now), q.source(), q.stockCode(), utc(q.exchangeTime()));
            return new Projection(q, List.copyOf(changed));
        });
    }

    private Projection projection(QuoteEvent event, QuoteEvent latest) {
        var rows = new ArrayList<DurableCandle>();
        for (int interval : DurableCandle.INTERVALS) rows.addAll(candles(event, interval));
        return new Projection(latest, List.copyOf(rows));
    }

    private List<DurableCandle> candles(QuoteEvent q, int interval) {
        return jdbc.query("SELECT * FROM stock_candle WHERE source=? AND stock_code=? AND interval_seconds=? AND bucket_start_at=?",
                CANDLE_ROW, q.source(), q.stockCode(), interval, utc(DurableCandle.bucket(q, interval)));
    }

    public List<Projection> load(String source, List<String> symbols) {
        return load(source, symbols, DurableCandle.INTERVALS);
    }

    public List<Projection> load(String source, List<String> symbols, List<Integer> intervals) {
        return transactions.execute(status -> {
            List<Projection> result = new ArrayList<>();
            for (String symbol : symbols) {
                var quotes = jdbc.query("SELECT * FROM stock_quote_state WHERE source=? AND stock_code=?", QUOTE_ROW, source, symbol);
                List<DurableCandle> bars = new ArrayList<>();
                for (int interval : intervals) {
                    bars.addAll(jdbc.query("""
                            SELECT * FROM stock_candle WHERE source=? AND stock_code=? AND interval_seconds=?
                            ORDER BY bucket_start_at DESC LIMIT ?
                            """, CANDLE_ROW, source, symbol, interval, QuoteStore.MAX_CANDLES_PER_INTERVAL));
                }
                result.add(new Projection(quotes.isEmpty() ? null : quotes.getFirst(), List.copyOf(bars)));
            }
            return List.copyOf(result);
        });
    }

    private void write(DurableCandle c, Instant now) {
        jdbc.update("""
                INSERT INTO stock_candle
                (source,stock_code,interval_seconds,bucket_start_at,business_date,open_price,high_price,low_price,
                 close_price,volume,trade_count,first_exchange_at,last_exchange_at,first_cumulative_volume,
                 last_cumulative_volume,is_closed,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE open_price=VALUES(open_price),high_price=VALUES(high_price),
                  low_price=VALUES(low_price),close_price=VALUES(close_price),volume=VALUES(volume),
                  trade_count=VALUES(trade_count),first_exchange_at=VALUES(first_exchange_at),
                  last_exchange_at=VALUES(last_exchange_at),first_cumulative_volume=VALUES(first_cumulative_volume),
                  last_cumulative_volume=VALUES(last_cumulative_volume),updated_at=VALUES(updated_at)
                """, c.source(), c.stockCode(), c.intervalSeconds(), utc(c.start()), c.businessDate(), c.open(), c.high(),
                c.low(), c.close(), c.volume(), c.tradeCount(), utc(c.firstExchangeAt()), utc(c.lastExchangeAt()),
                c.firstCumulativeVolume(), c.lastCumulativeVolume(), c.closed(), utc(now));
    }

    private void writeState(QuoteEvent q, String eventId, Instant now) {
        jdbc.update("""
                INSERT INTO stock_quote_state
                (source,stock_code,last_event_id,business_date,trade_time,exchange_at,received_at,price,price_change,
                 change_rate,day_open,day_high,day_low,cumulative_volume,trade_volume,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE last_event_id=VALUES(last_event_id),business_date=VALUES(business_date),
                  trade_time=VALUES(trade_time),exchange_at=VALUES(exchange_at),received_at=VALUES(received_at),
                  price=VALUES(price),price_change=VALUES(price_change),change_rate=VALUES(change_rate),
                  day_open=VALUES(day_open),day_high=VALUES(day_high),day_low=VALUES(day_low),
                  cumulative_volume=VALUES(cumulative_volume),trade_volume=VALUES(trade_volume),updated_at=VALUES(updated_at)
                """, q.source(), q.stockCode(), eventId, LocalDate.parse(q.businessDate()), q.tradeTime(),
                utc(q.exchangeTime()), utc(q.receivedAt()), q.price(), q.priceChange(), q.changeRate(), q.open(),
                q.high(), q.low(), q.volume(), q.tradeVolume(), utc(now));
    }

    private byte[] hash(QuoteEvent q) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsString(q).getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException("시세 무결성 확인 실패", e);
        }
    }

    private static void validate(QuoteEvent q) {
        if (q.stockCode() == null || !q.stockCode().matches("[0-9]{6}") || q.source() == null || q.source().isBlank()
                || q.source().length() > 40 || q.exchangeTime() == null || q.receivedAt() == null
                || q.price() <= 0 || q.volume() < 0 || q.tradeVolume() < 0 || !Double.isFinite(q.changeRate()))
            throw new IllegalArgumentException("저장할 시세 값이 올바르지 않습니다.");
        LocalDate date = LocalDate.parse(q.businessDate());
        if (!date.equals(q.exchangeTime().atZone(ZoneId.of("Asia/Seoul")).toLocalDate()))
            throw new IllegalArgumentException("체결 시각과 거래일이 다릅니다.");
    }

    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static Instant instant(ResultSet r, String name) throws SQLException {
        return r.getObject(name, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }
    private static final RowMapper<QuoteEvent> QUOTE_ROW = (r, i) -> new QuoteEvent(
            r.getString("stock_code"), r.getLong("price"), r.getLong("price_change"), r.getDouble("change_rate"),
            r.getLong("day_open"), r.getLong("day_high"), r.getLong("day_low"), r.getLong("cumulative_volume"),
            r.getLong("trade_volume"), r.getDate("business_date").toLocalDate().toString(), r.getString("trade_time"),
            instant(r, "exchange_at"), instant(r, "received_at"), r.getString("source"));
    private static final RowMapper<DurableCandle> CANDLE_ROW = (r, i) -> new DurableCandle(
            r.getString("source"), r.getString("stock_code"), r.getInt("interval_seconds"), instant(r, "bucket_start_at"),
            r.getDate("business_date").toLocalDate(), r.getLong("open_price"), r.getLong("high_price"), r.getLong("low_price"),
            r.getLong("close_price"), r.getLong("volume"), r.getLong("trade_count"), instant(r, "first_exchange_at"),
            instant(r, "last_exchange_at"), r.getLong("first_cumulative_volume"), r.getLong("last_cumulative_volume"), r.getBoolean("is_closed"));
}
