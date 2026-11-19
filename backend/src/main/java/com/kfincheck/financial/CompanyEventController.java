package com.kfincheck.financial;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;

@RestController
public class CompanyEventController {
    private final JdbcTemplate jdbc;

    public CompanyEventController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/company-events")
    public List<Event> list(@RequestParam("month") String month) {
        if (!month.matches("[0-9]{4}-(0[1-9]|1[0-2])") || month.startsWith("0000") || month.startsWith("9999")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected a month in YYYY-MM format.");
        }

        YearMonth period = YearMonth.parse(month);
        return jdbc.query("""
                SELECT e.event_id, e.stock_code, c.company_name, e.event_type, e.title,
                       e.event_date, e.event_time, e.announced_date, e.source, e.source_url,
                       e.venue, e.agenda
                FROM company_event e
                JOIN company c ON c.stock_code = e.stock_code
                WHERE e.event_date >= ? AND e.event_date < ?
                ORDER BY e.event_date, e.event_time IS NULL, e.event_time, e.event_id
                """, (row, index) -> new Event(
                row.getString("event_id"), row.getString("stock_code"), row.getString("company_name"),
                row.getString("event_type"), row.getString("title"), row.getObject("event_date", LocalDate.class),
                row.getObject("event_time", LocalTime.class), row.getObject("announced_date", LocalDate.class),
                row.getString("source"), row.getString("source_url"), row.getString("venue"), row.getString("agenda")),
                period.atDay(1), period.plusMonths(1).atDay(1));
    }

    public record Event(String id, String symbol, String name, String type, String title,
                        LocalDate date, LocalTime time, LocalDate announcedDate, String source, String sourceUrl,
                        String venue, String agenda) { }
}
