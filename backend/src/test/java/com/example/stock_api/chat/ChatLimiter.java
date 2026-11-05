package com.example.stock_api.chat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.example.stock_api.common.ApiException;

/**
 * 프로그램이 챗봇을 계속 호출해 OpenAI 비용을 쓰지 못하도록 계정별로 제한합니다.
 * 답변을 기다리는 동안 다시 보낼 수 없고, 1분·하루 횟수를 넘으면 429 로 답합니다. 서버 메모리에 보관합니다.
 */
@Component
public class ChatLimiter {

    private static final long MINUTE_MS = 60_000;

    private static final class Usage {
        private final Deque<Long> recent = new ArrayDeque<>();
        private LocalDate day;
        private int today;
        private boolean busy;
    }

    private final int perMinute;
    private final int perDay;
    private final Clock clock;
    private final Map<Long, Usage> usages = new HashMap<>();

    @Autowired
    public ChatLimiter(@Value("${chat.limit.per-minute:10}") int perMinute,
                       @Value("${chat.limit.per-day:100}") int perDay) {
        this(perMinute, perDay, Clock.system(ZoneId.of("Asia/Seoul")));
    }

    ChatLimiter(int perMinute, int perDay, Clock clock) {
        this.perMinute = perMinute;
        this.perDay = perDay;
        this.clock = clock;
    }

    public synchronized void acquire(long userId) {
        Usage usage = usages.computeIfAbsent(userId, id -> new Usage());
        long now = clock.millis();
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(usage.day)) {
            usage.day = today;
            usage.today = 0;
        }
        while (!usage.recent.isEmpty() && now - usage.recent.peekFirst() >= MINUTE_MS) {
            usage.recent.pollFirst();
        }
        if (usage.busy) {
            throw limited("CHAT_BUSY", "이전 질문의 답변을 기다리고 있습니다.");
        }
        if (usage.today >= perDay) {
            throw limited("CHAT_DAILY_LIMIT", "오늘 질문 횟수를 모두 사용했습니다. 내일 다시 이용해 주세요.");
        }
        if (usage.recent.size() >= perMinute) {
            throw limited("CHAT_MINUTE_LIMIT", "질문이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        }
        usage.recent.addLast(now);
        usage.today++;
        usage.busy = true;
    }

    public synchronized void release(long userId) {
        Usage usage = usages.get(userId);
        if (usage != null) {
            usage.busy = false;
        }
    }

    private static ApiException limited(String code, String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, code, message);
    }
}