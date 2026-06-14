package dev.shiftsmith.auth;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the login brute-force brake (issue #36), driving a controllable clock
 * so the cooldown behaviour is deterministic.
 */
class LoginThrottleTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final LoginThrottle throttle = new LoginThrottle(now::get);

    private void advance(long ms) { now.addAndGet(ms); }

    @Test
    void allowsAttemptsUpToTheThreshold() {
        for (int i = 0; i < LoginThrottle.FAILURE_THRESHOLD - 1; i++) {
            throttle.recordFailure("alice", "1.1.1.1");
            assertThat(throttle.retryAfterSeconds("alice", "1.1.1.1")).isZero();
        }
    }

    @Test
    void locksAfterThresholdAndUnlocksWhenTheCooldownExpires() {
        for (int i = 0; i < LoginThrottle.FAILURE_THRESHOLD; i++) throttle.recordFailure("alice", "1.1.1.1");

        long wait = throttle.retryAfterSeconds("alice", "1.1.1.1");
        assertThat(wait).isEqualTo(30); // first cooldown rung

        advance(29_000);
        assertThat(throttle.retryAfterSeconds("alice", "1.1.1.1")).isGreaterThan(0); // still locked
        advance(2_000);
        assertThat(throttle.retryAfterSeconds("alice", "1.1.1.1")).isZero();         // expired
    }

    @Test
    void cooldownGrowsOnRepeatedLockouts() {
        for (int i = 0; i < LoginThrottle.FAILURE_THRESHOLD; i++) throttle.recordFailure("alice", "1.1.1.1");
        assertThat(throttle.retryAfterSeconds("alice", "1.1.1.1")).isEqualTo(30);

        advance(31_000); // let the first cooldown lapse, then fail again
        throttle.recordFailure("alice", "1.1.1.1");
        assertThat(throttle.retryAfterSeconds("alice", "1.1.1.1")).isEqualTo(60); // next rung
    }

    @Test
    void successClearsTheCounter() {
        for (int i = 0; i < LoginThrottle.FAILURE_THRESHOLD - 1; i++) throttle.recordFailure("alice", "1.1.1.1");
        throttle.recordSuccess("alice", "1.1.1.1");
        // The streak is gone, so another failure doesn't immediately lock.
        throttle.recordFailure("alice", "1.1.1.1");
        assertThat(throttle.retryAfterSeconds("alice", "1.1.1.1")).isZero();
    }

    @Test
    void theSourceIpIsThrottledAcrossDifferentUsernames() {
        // A spray that tries a fresh username each time still trips the per-IP brake.
        for (int i = 0; i < LoginThrottle.FAILURE_THRESHOLD; i++) throttle.recordFailure("user" + i, "9.9.9.9");
        assertThat(throttle.retryAfterSeconds("brand-new-user", "9.9.9.9")).isGreaterThan(0);
        assertThat(throttle.retryAfterSeconds("brand-new-user", "8.8.8.8")).isZero(); // a different IP is unaffected
    }
}
