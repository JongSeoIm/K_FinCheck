package com.kfincheck.financial;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 공시 목록을 DB(disclosure)에 저장하고 종목·날짜로 조회한다.
 * <ul>
 *   <li>조회: 종목 코드·기간을 미리 만든 SQL 에 넣어 DB 에서 읽는다.</li>
 *   <li>아직 저장하지 않은 날짜가 있으면 그 구간만 OpenDART 에서 받아 저장한 뒤 DB 에서 읽는다.</li>
 *   <li>어떤 날짜를 다 받았는지는 disclosure_sync 에 남긴다. 그날이 끝난 뒤 받은 날짜는 다시 받지 않고,
 *       오늘·그날 안에 받은 날짜는 10분이 지나면 다시 받는다(그날 공시가 계속 올라오기 때문).</li>
 * </ul>
 */
@Service
public class DisclosureService {
    private static final Logger log = LoggerFactory.getLogger(DisclosureService.class);
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final int MAX_DAYS = 31;
    static final int MAX_PAST_DAYS = 400;
    static final int MAX_ITEMS = 50;
    static final Duration REFRESH = Duration.ofMinutes(10);
    private static final DateTimeFormatter DART_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    public record Disclosure(String receiptNo, LocalDate receivedDate, String reportName, String filerName,
                             String remark, String sourceUrl) { }

    public record Result(String stockCode, String companyName, LocalDate from, LocalDate to,
                         List<Disclosure> items, boolean truncated) { }

    /** OpenDART 에서 받은 공시 한 건. */
    record Filing(String receiptNo, String corpName, String reportName, String filerName, LocalDate receivedDate, String remark) { }

    /** OpenDART 공시 목록을 기간으로 받는 부분. 테스트에서는 가짜로 바꾼다. */
    interface FilingSource {
        List<Filing> fetch(String corpCode, LocalDate from, LocalDate to);
    }

    private final JdbcTemplate jdbc;
    private final Function<String, String> corpCodes;
    private final FilingSource dart;
    private final Clock clock;

    @Autowired
    public DisclosureService(JdbcTemplate jdbc, OpenDartClient dartClient, BusinessReportService reports) {
        this(jdbc, reports::corpCode, (corp, from, to) -> fromDart(dartClient, corp, from, to), Clock.system(KST));
    }

    DisclosureService(JdbcTemplate jdbc, Function<String, String> corpCodes, FilingSource dart, Clock clock) {
        this.jdbc = jdbc;
        this.corpCodes = corpCodes;
        this.dart = dart;
        this.clock = clock;
    }

    @PostConstruct
    void initialize() {
        try {
            new ResourceDatabasePopulator(new ClassPathResource("db/disclosure.sql")).execute(jdbc.getDataSource());
        } catch (DataAccessException error) {
            log.warn("disclosure 테이블을 준비하지 못했습니다. DB 설정을 확인하세요: {}", error.getMessage());
        }
    }

    /** 기간(최대 31일) 안의 공시. 최신 접수순, 최대 50건. */
    public Result list(String stockCode, LocalDate from, LocalDate to) {
        if (stockCode == null || !stockCode.matches("\\d{6}")) {
            throw new IllegalArgumentException("종목 코드는 숫자 6자리여야 합니다.");
        }
        LocalDate today = LocalDate.now(clock);
        if (from == null || to == null) throw new IllegalArgumentException("조회 기간이 필요합니다.");
        if (from.isAfter(to)) throw new IllegalArgumentException("시작일이 종료일보다 늦습니다.");
        if (to.isAfter(today)) throw new IllegalArgumentException("미래 날짜의 공시는 없습니다.");
        if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) throw new IllegalArgumentException("공시는 한 번에 31일까지 조회할 수 있습니다.");
        if (from.isBefore(today.minusDays(MAX_PAST_DAYS))) throw new IllegalArgumentException("최근 1년 안의 공시만 조회할 수 있습니다.");

        sync(stockCode, from, to);
        List<Disclosure> rows = jdbc.query("""
                SELECT receipt_no, received_date, report_name, filer_name, remark, source_url
                FROM disclosure
                WHERE stock_code = ? AND received_date BETWEEN ? AND ?
                ORDER BY received_date DESC, receipt_no DESC
                LIMIT ?
                """, (rs, index) -> new Disclosure(rs.getString("receipt_no"), rs.getDate("received_date").toLocalDate(),
                        rs.getString("report_name"), rs.getString("filer_name"), rs.getString("remark"), rs.getString("source_url")),
                stockCode, Date.valueOf(from), Date.valueOf(to), MAX_ITEMS + 1);
        boolean truncated = rows.size() > MAX_ITEMS;
        if (truncated) rows = rows.subList(0, MAX_ITEMS);
        return new Result(stockCode, companyName(stockCode), from, to, List.copyOf(rows), truncated);
    }

    /** 아직 다 받지 않은 첫 날짜부터 to 까지를 OpenDART 에서 받아 저장한다. */
    void sync(String stockCode, LocalDate from, LocalDate to) {
        LocalDateTime now = LocalDateTime.now(clock);
        Map<LocalDate, LocalDateTime> synced = new HashMap<>();
        jdbc.query("SELECT sync_date, synced_at FROM disclosure_sync WHERE stock_code = ? AND sync_date BETWEEN ? AND ?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> { synced.put(rs.getDate("sync_date").toLocalDate(), rs.getTimestamp("synced_at").toLocalDateTime()); },
                stockCode, Date.valueOf(from), Date.valueOf(to));
        LocalDate first = null;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (!complete(day, synced.get(day), now)) {
                first = day;
                break;
            }
        }
        if (first == null) return;

        String corpCode = corpCodes.apply(stockCode);
        List<Filing> filings = dart.fetch(corpCode, first, to);
        Timestamp at = Timestamp.valueOf(now);
        for (Filing filing : filings) {
            jdbc.update("""
                    INSERT INTO disclosure
                        (receipt_no, stock_code, corp_code, company_name, report_name, filer_name, received_date,
                         remark, source_url, collected_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE report_name = VALUES(report_name), filer_name = VALUES(filer_name),
                        remark = VALUES(remark), collected_at = VALUES(collected_at)
                    """, filing.receiptNo(), stockCode, corpCode, filing.corpName(), filing.reportName(), filing.filerName(),
                    Date.valueOf(filing.receivedDate()), filing.remark(),
                    "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + filing.receiptNo(), at);
        }
        for (LocalDate day = first; !day.isAfter(to); day = day.plusDays(1)) {
            jdbc.update("""
                    INSERT INTO disclosure_sync (stock_code, sync_date, synced_at) VALUES (?, ?, ?)
                    ON DUPLICATE KEY UPDATE synced_at = VALUES(synced_at)
                    """, stockCode, Date.valueOf(day), at);
        }
    }

    /** 그날이 끝난 뒤 받았으면 완료. 그날 안에 받았으면 10분 동안만 완료로 본다. */
    static boolean complete(LocalDate day, LocalDateTime syncedAt, LocalDateTime now) {
        if (syncedAt == null) return false;
        if (!syncedAt.isBefore(day.plusDays(1).atStartOfDay())) return true;
        return syncedAt.isAfter(now.minus(REFRESH));
    }

    private String companyName(String stockCode) {
        try {
            List<String> names = jdbc.queryForList("SELECT company_name FROM disclosure WHERE stock_code = ? AND company_name IS NOT NULL LIMIT 1",
                    String.class, stockCode);
            if (!names.isEmpty()) return names.getFirst();
            names = jdbc.queryForList("SELECT company_name FROM company WHERE stock_code = ?", String.class, stockCode);
            return names.isEmpty() ? null : names.getFirst();
        } catch (DataAccessException ignored) {
            return null;
        }
    }

    static List<Filing> fromDart(OpenDartClient dartClient, String corpCode, LocalDate from, LocalDate to) {
        List<Filing> filings = new ArrayList<>();
        int page = 1;
        int totalPages;
        do {
            JsonNode body = dartClient.disclosureList(corpCode, from.format(DART_DATE), to.format(DART_DATE), page);
            if ("013".equals(body.path("status").asText())) break;
            for (JsonNode row : body.path("list")) {
                String receipt = row.path("rcept_no").asText();
                String date = row.path("rcept_dt").asText();
                String title = row.path("report_nm").asText().strip();
                if (!receipt.matches("[0-9]{14}") || !date.matches("[0-9]{8}") || title.isEmpty()) continue;
                filings.add(new Filing(receipt, text(row, "corp_name", 100), cut(title, 300), text(row, "flr_nm", 100),
                        LocalDate.parse(date, DART_DATE), text(row, "rm", 20)));
            }
            totalPages = body.path("total_page").asInt(1);
            page++;
        } while (page <= totalPages && page <= 20);
        return filings;
    }

    private static String text(JsonNode row, String field, int max) {
        String value = row.path(field).asText("").strip();
        return value.isEmpty() ? null : cut(value, max);
    }

    private static String cut(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }
}
