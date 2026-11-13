package com.kfincheck.market;

import com.kfincheck.client.KisApiClient;
import com.kfincheck.client.dto.KisDailyChartResponse;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 일봉 시세를 DB(stock_daily_price)에 저장하고 날짜로 조회한다.
 * <ul>
 *   <li>조회: 종목 코드·날짜를 미리 만든 SQL 에 넣어 DB 에서 그날 종가와 직전 거래일 종가를 읽는다.</li>
 *   <li>DB 에 없는 날: 그때만 KIS 일봉을 받아 저장한 뒤 다시 DB 에서 읽는다.</li>
 *   <li>KIS 에도 그날 일봉이 없으면(휴장일 목록에 없는 임시공휴일 등) 7일 안의 직전 거래일 일봉을 돌려준다.
 *       응답의 tradeDate 가 요청 날짜와 달라지므로 호출하는 쪽이 "직전 거래일 기준"이라고 안내한다.</li>
 *   <li>당일 일봉은 장 마감(15:30) 뒤 15:40 부터 저장한다. 장중 값은 확정 전이라 저장하지 않는다.</li>
 * </ul>
 */
@Service
public class DailyPriceService {
    private static final Logger log = LoggerFactory.getLogger(DailyPriceService.class);
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final LocalTime STORE_AFTER = LocalTime.of(15, 40);
    static final int MAX_PAST_DAYS = 400;
    static final int FALLBACK_DAYS = 7;   // 그날 일봉이 없을 때 앞으로 거슬러 찾는 최대 일수
    private static final DateTimeFormatter KIS_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    /** 일봉 한 개. */
    public record DailyPrice(String stockCode, LocalDate tradeDate, Long open, Long high, Long low, long close, Long volume) { }

    /** 조회 결과. source 는 "DB"(이미 저장돼 있음), "KIS→DB"(없어서 받아 저장한 뒤 읽음),
     *  "직전 거래일"(그날 일봉이 없어 앞 날짜를 돌려줌). */
    public record DailyClose(String stockCode, LocalDate tradeDate, Long open, Long high, Long low, long close,
                             Long volume, Long previousClose, String source) { }

    /** KIS 일봉을 기간으로 받는 부분. 테스트에서는 가짜로 바꾼다. */
    interface CandleSource {
        List<DailyPrice> fetch(String stockCode, LocalDate from, LocalDate to);
    }

    private final JdbcTemplate jdbc;
    private final CandleSource kis;
    private final Clock clock;

    @Autowired
    public DailyPriceService(JdbcTemplate jdbc, KisApiClient kisApiClient) {
        this(jdbc, (code, from, to) -> fromKis(kisApiClient, code, from, to), Clock.system(KST));
    }

    DailyPriceService(JdbcTemplate jdbc, CandleSource kis, Clock clock) {
        this.jdbc = jdbc;
        this.kis = kis;
        this.clock = clock;
    }

    @PostConstruct
    void initialize() {
        try {
            new ResourceDatabasePopulator(new ClassPathResource("db/daily-price.sql")).execute(jdbc.getDataSource());
        } catch (DataAccessException error) {
            log.warn("stock_daily_price 테이블을 준비하지 못했습니다. DB 설정을 확인하세요: {}", error.getMessage());
        }
    }

    /** 그날 종가. DB 에 없으면 KIS 에서 받아 저장한 뒤 DB 에서 읽는다.
     *  KIS 에도 없으면 7일 안의 직전 거래일 종가. 그것도 없으면 NoSuchElementException. */
    public DailyClose close(String stockCode, LocalDate date) {
        validate(stockCode);
        if (date == null) throw new IllegalArgumentException("날짜가 필요합니다. 예: 2026-11-06");
        LocalDate today = LocalDate.now(clock);
        if (date.isAfter(today)) throw new IllegalArgumentException("미래 날짜의 시세는 없습니다.");
        if (date.isBefore(today.minusDays(MAX_PAST_DAYS))) throw new IllegalArgumentException("최근 1년 안의 날짜로 조회해 주세요.");
        if (!storable(date)) throw new IllegalArgumentException("오늘 일봉은 장 마감 뒤에 확정됩니다.");

        DailyClose saved = find(stockCode, date, "DB", true);
        if (saved != null) return saved;
        save(kis.fetch(stockCode, date.minusDays(14), date));
        DailyClose filled = find(stockCode, date, "KIS→DB", true);
        if (filled != null) return filled;
        // KIS 에도 그날 일봉이 없다 = 그날은 장이 열리지 않았다. 지어내지 않고 직전 거래일 실제 일봉을 쓴다.
        DailyClose earlier = find(stockCode, date, "직전 거래일", false);
        if (earlier == null || earlier.tradeDate().isBefore(date.minusDays(FALLBACK_DAYS)))
            throw new NoSuchElementException(date + " 거래일의 일봉이 없습니다.");
        return earlier;
    }

    /** 기간의 일봉을 KIS 에서 받아 저장한다. KIS 는 한 번에 최대 100개라 기간을 70일씩 나눠 받는다. */
    public int collect(String stockCode, LocalDate from, LocalDate to) {
        validate(stockCode);
        int saved = 0;
        for (LocalDate start = from; !start.isAfter(to); start = start.plusDays(70)) {
            LocalDate end = start.plusDays(69).isAfter(to) ? to : start.plusDays(69);
            saved += save(kis.fetch(stockCode, start, end));
        }
        return saved;
    }

    /** exact=true 면 그날 일봉만, false 면 그날 이전(포함) 가장 가까운 일봉. 직전 거래일 종가도 함께 읽는다. */
    private DailyClose find(String stockCode, LocalDate date, String source, boolean exact) {
        List<DailyPrice> rows = jdbc.query("""
                SELECT stock_code, trade_date, open_price, high_price, low_price, close_price, volume
                FROM stock_daily_price
                WHERE stock_code = ? AND trade_date <= ?
                ORDER BY trade_date DESC
                LIMIT 2
                """, (rs, index) -> row(rs), stockCode, Date.valueOf(date));
        if (rows.isEmpty() || (exact && !rows.getFirst().tradeDate().equals(date))) return null;
        DailyPrice day = rows.getFirst();
        Long previous = rows.size() > 1 ? rows.get(1).close() : null;
        return new DailyClose(day.stockCode(), day.tradeDate(), day.open(), day.high(), day.low(), day.close(),
                day.volume(), previous, source);
    }

    int save(List<DailyPrice> prices) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(clock));
        int saved = 0;
        for (DailyPrice price : prices) {
            if (price == null || price.close() <= 0 || !storable(price.tradeDate())) continue;
            jdbc.update("""
                    INSERT INTO stock_daily_price
                        (stock_code, trade_date, open_price, high_price, low_price, close_price, volume, collected_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE open_price = VALUES(open_price), high_price = VALUES(high_price),
                        low_price = VALUES(low_price), close_price = VALUES(close_price), volume = VALUES(volume),
                        collected_at = VALUES(collected_at)
                    """, price.stockCode(), Date.valueOf(price.tradeDate()), price.open(), price.high(), price.low(),
                    price.close(), price.volume(), now);
            saved++;
        }
        return saved;
    }

    /** 확정된 일봉만 저장한다. 오늘 일봉은 15:40 이후에만 확정으로 본다. */
    boolean storable(LocalDate date) {
        LocalDateTime now = LocalDateTime.now(clock);
        return date.isBefore(now.toLocalDate()) || (date.equals(now.toLocalDate()) && !now.toLocalTime().isBefore(STORE_AFTER));
    }

    private static void validate(String stockCode) {
        if (stockCode == null || !stockCode.matches("\\d{6}")) throw new IllegalArgumentException("종목 코드는 숫자 6자리여야 합니다.");
    }

    private static DailyPrice row(ResultSet rs) throws SQLException {
        return new DailyPrice(rs.getString("stock_code"), rs.getDate("trade_date").toLocalDate(),
                nullableLong(rs, "open_price"), nullableLong(rs, "high_price"), nullableLong(rs, "low_price"),
                rs.getLong("close_price"), nullableLong(rs, "volume"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static List<DailyPrice> fromKis(KisApiClient kisApiClient, String stockCode, LocalDate from, LocalDate to) {
        KisDailyChartResponse response = kisApiClient.getDailyCandles(stockCode, from.format(KIS_DATE), to.format(KIS_DATE), "D");
        if (response == null || !response.isSuccess() || response.getOutput2() == null) {
            throw new IllegalStateException("KIS 일봉 조회에 실패했습니다.");
        }
        List<DailyPrice> prices = new ArrayList<>();
        for (KisDailyChartResponse.DailyCandleOutput item : response.getOutput2()) {
            String raw = item.getStckBsopDate();
            Long close = number(item.getStckClpr());
            if (raw == null || !raw.matches("\\d{8}") || close == null || close <= 0) continue;
            prices.add(new DailyPrice(stockCode, LocalDate.parse(raw, KIS_DATE), number(item.getStckOprc()),
                    number(item.getStckHgpr()), number(item.getStckLwpr()), close, number(item.getAcmlVol())));
        }
        return prices;
    }

    private static Long number(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
