package com.kfincheck.chat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * AI 비용 관리.
 * <ul>
 *   <li>호출 제한: 사용자별 1분 N회(메모리), 하루 N회·하루 토큰 N개(ai_usage_log), 서비스 전체 월 토큰 N개. 넘으면 429.</li>
 *   <li>하루 질문 수는 OpenAI 를 실제로 부른 질문(llm_calls &gt; 0)만 센다. 범위 밖 질문·조회 실패 안내는 한도에 넣지 않는다.</li>
 *   <li>사용량 기록: AI 서버가 돌려준 usage(모델, 입력·출력·임베딩 토큰, OpenAI 호출 수)를 질문마다 저장.</li>
 * </ul>
 * ai_usage_log 는 대화를 지워도 남는다. 대화 삭제로 하루 한도를 초기화할 수 없게 하기 위해서다.
 */
@Component
public class ChatUsageGuard {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;
    private final int perMinute;
    private final int perDay;
    private final long dailyTokens;
    private final long monthlyTokens;
    private final Clock clock;
    private final Map<Long, Deque<Instant>> recent = new ConcurrentHashMap<>();

    @Autowired
    public ChatUsageGuard(JdbcTemplate jdbc,
            @Value("${chat.limit.per-minute:5}") int perMinute,
            @Value("${chat.limit.per-day:100}") int perDay,
            @Value("${chat.limit.daily-tokens:200000}") long dailyTokens,
            @Value("${chat.limit.monthly-tokens:0}") long monthlyTokens) {
        this(jdbc, perMinute, perDay, dailyTokens, monthlyTokens, Clock.system(KST));
    }

    ChatUsageGuard(JdbcTemplate jdbc, int perMinute, int perDay, long dailyTokens, Clock clock) {
        this(jdbc, perMinute, perDay, dailyTokens, 0, clock);
    }

    ChatUsageGuard(JdbcTemplate jdbc, int perMinute, int perDay, long dailyTokens, long monthlyTokens, Clock clock) {
        this.jdbc = jdbc;
        this.perMinute = perMinute;
        this.perDay = perDay;
        this.dailyTokens = dailyTokens;
        this.monthlyTokens = monthlyTokens;
        this.clock = clock;
    }

    /** 제한 없음. DB 없이 ChatService 를 만드는 단위 테스트용. */
    static ChatUsageGuard unlimited() {
        return new ChatUsageGuard(null, 0, 0, 0, 0, Clock.system(KST));
    }

    @PostConstruct
    void initialize() {
        if (jdbc != null) new ResourceDatabasePopulator(new ClassPathResource("db/ai-usage.sql")).execute(jdbc.getDataSource());
    }

    /** AI 서버를 부르기 전에 확인한다. */
    public void check(long userId) {
        Instant now = clock.instant();
        if (perMinute > 0) {
            Deque<Instant> calls = recent.computeIfAbsent(userId, key -> new ArrayDeque<>());
            synchronized (calls) {
                while (!calls.isEmpty() && !calls.peekFirst().isAfter(now.minusSeconds(60))) calls.pollFirst();
                if (calls.size() >= perMinute)
                    throw tooMany("질문이 너무 빠릅니다. 1분에 " + perMinute + "번까지 질문할 수 있으니 잠시 후 다시 시도해 주세요.");
                calls.addLast(now);
            }
        }
        if (jdbc == null) return;
        if (monthlyTokens > 0) {
            Long month = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(input_tokens + output_tokens + embedding_tokens), 0)
                    FROM ai_usage_log WHERE created_at>=?
                    """, Long.class, Timestamp.valueOf(LocalDate.now(clock).withDayOfMonth(1).atStartOfDay()));
            if (month != null && month >= monthlyTokens)
                throw tooMany("AI 서비스의 이번 달 사용 한도에 도달했습니다. 다음 달에 다시 이용해 주세요.");
        }
        if (perDay <= 0 && dailyTokens <= 0) return;
        long[] today = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN llm_calls > 0 THEN 1 ELSE 0 END), 0),
                       COALESCE(SUM(input_tokens + output_tokens + embedding_tokens), 0)
                FROM ai_usage_log WHERE user_id=? AND created_at>=?
                """, (row, index) -> new long[] { row.getLong(1), row.getLong(2) },
                userId, Timestamp.valueOf(LocalDate.now(clock).atStartOfDay()));
        if (perDay > 0 && today[0] >= perDay)
            throw tooMany("오늘 질문 한도(" + perDay + "번)를 모두 사용했습니다. 내일 다시 이용해 주세요.");
        if (dailyTokens > 0 && today[1] >= dailyTokens)
            throw tooMany("오늘 AI 사용량 한도를 모두 사용했습니다. 내일 다시 이용해 주세요.");
    }

    /** AI 서버 응답의 usage 를 저장한다. OpenAI 를 부르지 않은 답변(고정 안내 등)은 토큰 0으로 남는다. */
    public void record(long userId, String conversationId, ChatModels.AiResponse response) {
        if (jdbc == null) return;
        JsonNode usage = response.usage();
        String model = usage == null || usage.path("model").isNull() || usage.path("model").isMissingNode()
                ? null : usage.path("model").asText();
        if (model != null && model.length() > 120) model = model.substring(0, 120);
        jdbc.update("""
                INSERT INTO ai_usage_log(user_id,conversation_id,status,model,input_tokens,output_tokens,embedding_tokens,llm_calls,created_at)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, userId, conversationId, response.status() == null ? "unknown" : response.status(), model,
                tokens(usage, "input_tokens"), tokens(usage, "output_tokens"), tokens(usage, "embedding_tokens"),
                Math.min(tokens(usage, "llm_calls"), 255), Timestamp.valueOf(LocalDateTime.now(clock)));
    }

    private static int tokens(JsonNode usage, String field) {
        return usage == null ? 0 : Math.max(usage.path(field).asInt(0), 0);
    }

    private static ResponseStatusException tooMany(String message) {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
    }
}
