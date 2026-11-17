package com.kfincheck.config;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 키(사용자 또는 IP + API 묶음)별로 최근 1분 동안의 호출 수를 센다. 서버 메모리에만 둔다.
 * 재시작하면 초기화되지만, 1분 단위 제한이라 문제되지 않는다.
 */
public class ApiRateLimiter {
    static final long WINDOW_MILLIS = 60_000;
    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Map<String, Deque<Long>> calls = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public ApiRateLimiter() {
        this(System::currentTimeMillis);
    }

    ApiRateLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * 호출 1번을 기록한다.
     *
     * @return 허용이면 0, 한도를 넘었으면 다시 시도할 수 있을 때까지 남은 초(1 이상)
     */
    public long acquire(String key, int limitPerMinute) {
        if (limitPerMinute <= 0) return 0;
        long now = clock.getAsLong();
        if (calls.size() > CLEANUP_THRESHOLD) cleanup(now);
        Deque<Long> times = calls.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst() <= now - WINDOW_MILLIS) times.pollFirst();
            if (times.size() >= limitPerMinute) {
                long waitMillis = times.peekFirst() + WINDOW_MILLIS - now;
                return Math.max(1, (waitMillis + 999) / 1000);
            }
            times.addLast(now);
            return 0;
        }
    }

    int trackedKeys() {
        return calls.size();
    }

    /** 1분 넘게 호출이 없던 키를 지운다. */
    void cleanup(long now) {
        calls.entrySet().removeIf(entry -> {
            Deque<Long> times = entry.getValue();
            synchronized (times) {
                return times.isEmpty() || times.peekLast() <= now - WINDOW_MILLIS;
            }
        });
    }
}
