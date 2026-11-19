package com.kfincheck.financial;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;

@Service
public class CompanyEventImporter {
    private record Event(String receipt, String type, String title, LocalDate date) { }

    private final OpenDartClient dart;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public CompanyEventImporter(OpenDartClient dart, JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.dart = dart;
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    public int collect(String stock, int year) {
        var codes = jdbc.queryForList("SELECT dart_corp_code FROM company WHERE stock_code = ?", String.class, stock);
        if (codes.isEmpty() || codes.getFirst() == null || !codes.getFirst().matches("[0-9]{8}")) {
            throw new IllegalArgumentException("A company with a valid DART code is required.");
        }
        String start = LocalDate.of(year, 1, 1).format(DateTimeFormatter.BASIC_ISO_DATE);
        String end = LocalDate.of(year, 12, 31).format(DateTimeFormatter.BASIC_ISO_DATE);
        var events = new LinkedHashMap<String, Event>();
        for (String disclosureType : List.of("B", "I")) {
            int page = 1;
            JsonNode response;
            do {
                response = dart.disclosurePage(codes.getFirst(), start, end, disclosureType, page++);
                for (JsonNode row : response.path("list")) {
                    String title = row.path("report_nm").asText().strip();
                    String compactTitle = title.replaceAll("[\\sㆍ·]", "");
                    // Revisions need a verified link to the original filing before they can replace it.
                    if (compactTitle.startsWith("[")) continue;
                    String eventType = switch (compactTitle) {
                        case "주요사항보고서(자기주식취득결정)", "자기주식취득결정" -> "SHARE_BUYBACK";
                        case "현금현물배당결정", "현금배당결정", "현물배당결정" -> "DIVIDEND";
                        default -> null;
                    };
                    if (eventType == null) continue;
                    String receipt = row.path("rcept_no").asText();
                    if (!receipt.matches("[0-9]{14}")) {
                        throw new IllegalStateException("Invalid receipt number in disclosure search.");
                    }
                    LocalDate date = LocalDate.parse(row.path("rcept_dt").asText(), DateTimeFormatter.BASIC_ISO_DATE);
                    events.put(receipt, new Event(receipt, eventType, title, date));
                }
            } while (page <= response.path("total_page").asInt());
        }

        transaction.executeWithoutResult(status -> {
            for (Event event : events.values()) {
                jdbc.update("""
                        INSERT INTO company_event
                            (stock_code, event_type, title, event_date, announced_date, source, source_url, receipt_no)
                        VALUES (?, ?, ?, ?, ?, 'OPENDART', ?, ?)
                        ON DUPLICATE KEY UPDATE receipt_no = VALUES(receipt_no)
                        """, stock, event.type(), event.title(), event.date(), event.date(),
                        "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + event.receipt(), event.receipt());
            }
        });
        return events.size();
    }
}
