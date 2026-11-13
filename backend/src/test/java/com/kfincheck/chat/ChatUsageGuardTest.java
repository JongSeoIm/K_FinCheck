package com.kfincheck.chat;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

class ChatUsageGuardTest {
    /** 시간을 직접 옮길 수 있는 시계. */
    static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-11-10T01:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.ofHours(9); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void blocksSixthQuestionWithinOneMinuteAndAllowsAfterwards() {
        var clock = new MovableClock();
        var guard = new ChatUsageGuard(null, 5, 0, 0, clock);
        for (int i = 0; i < 5; i++) guard.check(7L);
        assertThatThrownBy(() -> guard.check(7L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("1분에 5번");
        assertThatCode(() -> guard.check(8L)).doesNotThrowAnyException();  // 다른 사용자는 영향 없음
        clock.now = clock.now.plusSeconds(61);
        assertThatCode(() -> guard.check(7L)).doesNotThrowAnyException();
    }

    @Test
    void unlimitedGuardNeverBlocks() {
        var guard = ChatUsageGuard.unlimited();
        for (int i = 0; i < 50; i++) guard.check(1L);
    }

    private static JdbcTemplate usageDatabase() throws Exception {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        String ddl = Files.readString(Path.of("src/main/resources/db/ai-usage.sql"))
                .replaceAll("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci", "")
                .replaceAll(",\\s*FOREIGN KEY.*?CASCADE", "");  // app_user 표 없이 확인
        for (String statement : ddl.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
        return jdbc;
    }

    private static void log(JdbcTemplate jdbc, long userId, int llmCalls, int tokens, LocalDateTime at) {
        jdbc.update("INSERT INTO ai_usage_log(user_id,status,input_tokens,output_tokens,embedding_tokens,llm_calls,created_at) VALUES(?,?,?,?,?,?,?)",
                userId, "answered", tokens, 0, 0, llmCalls, Timestamp.valueOf(at));
    }

    @Test
    void dailyCountSkipsAnswersWithoutModelCalls() throws Exception {
        var jdbc = usageDatabase();
        var clock = new MovableClock();  // 2026-11-10 10:00 KST
        LocalDateTime today = LocalDateTime.of(2026, 11, 10, 9, 0);
        log(jdbc, 7L, 1, 10, today);
        log(jdbc, 7L, 0, 0, today);  // 범위 밖 안내 등 OpenAI 를 부르지 않은 답변
        var guard = new ChatUsageGuard(jdbc, 0, 2, 0, 0, clock);
        assertThatCode(() -> guard.check(7L)).doesNotThrowAnyException();
        log(jdbc, 7L, 2, 10, today);
        assertThatThrownBy(() -> guard.check(7L)).hasMessageContaining("오늘 질문 한도(2번)");
    }

    @Test
    void monthlyTokenCapCoversAllUsers() throws Exception {
        var jdbc = usageDatabase();
        var clock = new MovableClock();
        log(jdbc, 1L, 1, 600, LocalDateTime.of(2026, 10, 31, 12, 0));  // 지난달은 세지 않는다
        log(jdbc, 1L, 1, 600, LocalDateTime.of(2026, 11, 2, 12, 0));
        var guard = new ChatUsageGuard(jdbc, 0, 0, 0, 1000, clock);
        assertThatCode(() -> guard.check(2L)).doesNotThrowAnyException();
        log(jdbc, 3L, 1, 500, LocalDateTime.of(2026, 11, 9, 12, 0));
        assertThatThrownBy(() -> guard.check(2L)).hasMessageContaining("이번 달 사용 한도");
    }
}
