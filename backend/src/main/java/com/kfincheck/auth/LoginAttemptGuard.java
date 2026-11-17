package com.kfincheck.auth;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 로그인 시도 제한 (비밀번호 무차별 대입 방지). 서버 메모리에만 둔다.
 * <ul>
 *   <li>아이디 + IP: 10분 안에 5번 틀리면 10분 잠금. 한 아이디의 비밀번호를 계속 대입하는 공격을 막는다.
 *       IP 를 같이 보므로 남의 아이디로 일부러 틀려도 진짜 주인(다른 IP)은 잠기지 않는다.</li>
 *   <li>IP: 10분 안에 20번 틀리면 10분 잠금. 아이디를 바꿔 가며 여러 계정에 시도하는 공격을 막는다.</li>
 *   <li>로그인에 성공하면 그 아이디 + IP 의 실패 기록을 지운다.</li>
 * </ul>
 * 값은 application.yml 의 auth.login-limit.* (환경변수 LOGIN_LIMIT_*)로 바꾸고, 0 이면 끈다.
 */
@Component
public class LoginAttemptGuard {
    private static final long WINDOW_MILLIS = 10 * 60_000L;
    private static final int CLEANUP_THRESHOLD = 10_000;

    private final LongSupplier clock;
    private final int perAccount;
    private final int perIp;
    private final long lockMillis;
    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();
    private final Map<String, Long> lockedUntil = new ConcurrentHashMap<>();

    @Autowired
    public LoginAttemptGuard(@Value("${auth.login-limit.per-account:5}") int perAccount,
                             @Value("${auth.login-limit.per-ip:20}") int perIp,
                             @Value("${auth.login-limit.lock-minutes:10}") int lockMinutes) {
        this(System::currentTimeMillis, perAccount, perIp, lockMinutes * 60_000L);
    }

    LoginAttemptGuard(LongSupplier clock, int perAccount, int perIp, long lockMillis) {
        this.clock = clock;
        this.perAccount = perAccount;
        this.perIp = perIp;
        this.lockMillis = lockMillis;
    }

    /** 잠겨 있으면 남은 초(1 이상), 아니면 0. */
    public long lockedSeconds(String loginId, String ip) {
        return Math.max(left(accountKey(loginId, ip)), left(ipKey(ip)));
    }

    /** 실패를 기록한다. 이번 실패로 잠기면 남은 초, 아니면 0. */
    public long recordFailure(String loginId, String ip) {
        long now = clock.getAsLong();
        if (failures.size() > CLEANUP_THRESHOLD) cleanup(now);
        count(accountKey(loginId, ip), perAccount, now);
        count(ipKey(ip), perIp, now);
        return lockedSeconds(loginId, ip);
    }

    public void recordSuccess(String loginId, String ip) {
        String key = accountKey(loginId, ip);
        failures.remove(key);
        lockedUntil.remove(key);
    }

    private void count(String key, int limit, long now) {
        if (limit <= 0) return;
        Deque<Long> times = failures.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst() <= now - WINDOW_MILLIS) times.pollFirst();
            times.addLast(now);
            if (times.size() < limit) return;
            times.clear();
        }
        lockedUntil.put(key, now + lockMillis);
    }

    private long left(String key) {
        Long until = lockedUntil.get(key);
        if (until == null) return 0;
        long millis = until - clock.getAsLong();
        if (millis <= 0) {
            lockedUntil.remove(key, until);
            return 0;
        }
        return (millis + 999) / 1000;
    }

    private void cleanup(long now) {
        failures.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                return entry.getValue().isEmpty() || entry.getValue().peekLast() <= now - WINDOW_MILLIS;
            }
        });
        lockedUntil.values().removeIf(until -> until <= now);
    }

    private static String accountKey(String loginId, String ip) {
        String id = loginId == null ? "" : loginId.trim().toLowerCase(Locale.ROOT);
        return "id:" + id + "|" + ip;
    }

    private static String ipKey(String ip) {
        return "ip:" + ip;
    }
}
