package com.kfincheck.config;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 1분 한도 다음 단계의 대응. 서버 메모리에만 둔다(재시작하면 초기화).
 * <ul>
 *   <li>하루 한도: 계정(또는 IP)별 하루 전체 호출 수. 1분 한도를 지키면서 하루 종일 부르는 경우를 막는다. 한국 날짜 기준 자정에 초기화.</li>
 *   <li>일시 정지: 10분 안에 1분 한도로 막힌 횟수가 N번을 넘으면 10분 동안 모든 조회·챗봇 API 를 막는다.
 *       정상 사용자는 1분 한도에 한 번도 걸리지 않으므로 반복해서 막히는 것은 프로그램으로 본다.</li>
 * </ul>
 */
public class ApiAbuseGuard {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int CLEANUP_THRESHOLD = 10_000;

    private final LongSupplier clock;
    private final int dailyLimit;
    private final int violationsToSuspend;
    private final long violationWindowMillis;
    private final long suspendMillis;

    private final Map<String, Integer> daily = new HashMap<>();
    private final Map<String, Deque<Long>> violations = new HashMap<>();
    private final Map<String, Long> suspendedUntil = new HashMap<>();
    private LocalDate dailyCleanupDate;
    private long nextViolationCleanup;

    public ApiAbuseGuard(LongSupplier clock, int dailyLimit, int violationsToSuspend, long violationWindowMillis, long suspendMillis) {
        this.clock = clock;
        this.dailyLimit = dailyLimit;
        this.violationsToSuspend = violationsToSuspend;
        this.violationWindowMillis = violationWindowMillis;
        this.suspendMillis = suspendMillis;
    }

    /** 정지 중이면 남은 초(1 이상), 아니면 0. */
    public synchronized long suspendedSeconds(String who) {
        Long until = suspendedUntil.get(who);
        if (until == null) return 0;
        long left = until - clock.getAsLong();
        if (left <= 0) {
            suspendedUntil.remove(who);
            return 0;
        }
        return (left + 999) / 1000;
    }

    /** 하루 한도 안이면 1번 세고 true, 넘었으면 세지 않고 false. */
    public synchronized boolean countDaily(String who) {
        if (dailyLimit <= 0) return true;
        LocalDate today = Instant.ofEpochMilli(clock.getAsLong()).atZone(KST).toLocalDate();
        if (!today.equals(dailyCleanupDate)) {
            daily.clear();
            dailyCleanupDate = today;
        }
        int current = daily.getOrDefault(who, 0);
        if (current >= dailyLimit) return false;
        daily.put(who, current + 1);
        return true;
    }

    /** 1분 한도로 막힐 때마다 부른다. 이번 위반으로 정지가 시작되면 true. */
    public synchronized boolean recordViolation(String who) {
        if (violationsToSuspend <= 0 || suspendMillis <= 0) return false;
        long now = clock.getAsLong();
        if (violations.size() + suspendedUntil.size() > CLEANUP_THRESHOLD && now >= nextViolationCleanup) {
            violations.values().removeIf(times -> times.peekLast() <= now - violationWindowMillis);
            suspendedUntil.values().removeIf(until -> until <= now);
            nextViolationCleanup = now + ApiRateLimiter.WINDOW_MILLIS;
        }
        Deque<Long> times = violations.computeIfAbsent(who, key -> new ArrayDeque<>());
        while (!times.isEmpty() && times.peekFirst() <= now - violationWindowMillis) times.pollFirst();
        times.addLast(now);
        if (times.size() < violationsToSuspend) return false;
        violations.remove(who);
        suspendedUntil.put(who, now + suspendMillis);
        return true;
    }

    long suspendMinutes() {
        return suspendMillis / 60_000;
    }

    long secondsUntilMidnight() {
        var now = Instant.ofEpochMilli(clock.getAsLong()).atZone(KST);
        return java.time.Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(KST)).toSeconds();
    }
}
