package com.kfincheck.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LoginAttemptGuardTest {
    private long now = 1_000_000;
    private final LoginAttemptGuard guard = new LoginAttemptGuard(() -> now, 5, 20, 10 * 60_000L);

    @Test
    void locksAccountFromSameIpAfterFiveFailures() {
        for (int i = 0; i < 4; i++) assertThat(guard.recordFailure("gayeon", "2.2.2.2")).isZero();
        assertThat(guard.recordFailure("gayeon", "2.2.2.2")).isEqualTo(600);  // 5번째 실패에서 10분 잠금
        assertThat(guard.lockedSeconds("gayeon", "2.2.2.2")).isEqualTo(600);
        assertThat(guard.lockedSeconds("GAYEON ", "2.2.2.2")).isEqualTo(600);  // 대소문자·공백 무시

        assertThat(guard.lockedSeconds("gayeon", "1.1.1.1")).isZero();  // 진짜 주인(다른 IP)은 로그인 가능

        now += 10 * 60_000L;
        assertThat(guard.lockedSeconds("gayeon", "2.2.2.2")).isZero();  // 10분 뒤 풀림
    }

    @Test
    void locksIpTryingManyAccounts() {
        for (int i = 0; i < 19; i++) assertThat(guard.recordFailure("user" + i, "2.2.2.2")).isZero();
        assertThat(guard.recordFailure("user19", "2.2.2.2")).isEqualTo(600);  // 아이디를 바꿔도 IP 20번째에서 잠금
        assertThat(guard.lockedSeconds("someone_else", "2.2.2.2")).isEqualTo(600);
        assertThat(guard.lockedSeconds("someone_else", "3.3.3.3")).isZero();
    }

    @Test
    void successClearsFailuresAndOldFailuresExpire() {
        for (int i = 0; i < 4; i++) guard.recordFailure("gayeon", "1.1.1.1");
        guard.recordSuccess("gayeon", "1.1.1.1");
        for (int i = 0; i < 4; i++) assertThat(guard.recordFailure("gayeon", "1.1.1.1")).isZero();  // 성공 후 다시 0부터

        now += 10 * 60_000L;  // 10분 지난 실패는 세지 않음
        assertThat(guard.recordFailure("gayeon", "1.1.1.1")).isZero();
    }

    @Test
    void zeroTurnsOff() {
        var off = new LoginAttemptGuard(() -> now, 0, 0, 600_000L);
        for (int i = 0; i < 50; i++) assertThat(off.recordFailure("gayeon", "2.2.2.2")).isZero();
    }
}
