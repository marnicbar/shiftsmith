package dev.shiftsmith.auth;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * In-memory brute-force brake for {@code POST /api/auth/login} (issue #36). Tracks
 * consecutive failures per username <em>and</em> per source IP; once either crosses
 * {@link #FAILURE_THRESHOLD} the key is locked for a cooldown that grows on repeat
 * (30s → 1m → 5m, capped), so an attacker is slowed regardless of whether they spray
 * one account or many. A successful login clears the counter, so a legitimate user is
 * never permanently locked out — a cooldown always auto-expires.
 *
 * <p>Single-instance, process-local state (this app runs one node). Behind a reverse
 * proxy without {@code quarkus.http.proxy.proxy-address-forwarding}, every request
 * shares the proxy's address, so the per-IP brake degrades to a global one — safe, just
 * coarser; the per-username brake is unaffected.
 */
@ApplicationScoped
public class LoginThrottle {

    private static final Logger LOG = Logger.getLogger(LoginThrottle.class);

    /** Consecutive failures (per key) before a cooldown kicks in. */
    static final int FAILURE_THRESHOLD = 5;

    /** Cooldown ladder once over the threshold; the last entry is the cap. */
    private static final long[] COOLDOWN_MS = { 30_000L, 60_000L, 300_000L };

    /** Bound the map so a spray across many IPs can't grow it without limit. */
    private static final int MAX_TRACKED = 10_000;

    private static final class Attempt {
        int failures;
        long lockedUntil;
    }

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public LoginThrottle() {
        this(System::currentTimeMillis);
    }

    /** Package-private for tests, which drive a controllable clock. */
    LoginThrottle(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Seconds the caller must wait before another login attempt is allowed, or 0 if not
     * currently locked. The longer of the username and IP locks applies.
     */
    public long retryAfterSeconds(String username, String ip) {
        long now = clock.getAsLong();
        long until = Math.max(lockedUntil(userKey(username), now), lockedUntil(ipKey(ip), now));
        return until > now ? (until - now + 999) / 1000 : 0;
    }

    /** Record a failed login, locking the username/IP once they cross the threshold. */
    public void recordFailure(String username, String ip) {
        if (attempts.size() > MAX_TRACKED) prune();
        boolean lockedUser = registerFailure(userKey(username));
        boolean lockedIp = registerFailure(ipKey(ip));
        if (lockedUser || lockedIp) {
            LOG.warnf("Login throttled after repeated failures (username=%s, ip=%s)", username, ip);
        } else {
            LOG.warnf("Failed login attempt (username=%s, ip=%s)", username, ip);
        }
    }

    /** Clear the counters for a successful login. */
    public void recordSuccess(String username, String ip) {
        attempts.remove(userKey(username));
        attempts.remove(ipKey(ip));
    }

    private boolean registerFailure(String key) {
        Attempt a = attempts.computeIfAbsent(key, k -> new Attempt());
        synchronized (a) {
            a.failures++;
            if (a.failures >= FAILURE_THRESHOLD) {
                int over = Math.min(a.failures - FAILURE_THRESHOLD, COOLDOWN_MS.length - 1);
                a.lockedUntil = clock.getAsLong() + COOLDOWN_MS[over];
                return true;
            }
            return false;
        }
    }

    private long lockedUntil(String key, long now) {
        Attempt a = attempts.get(key);
        if (a == null) return 0;
        synchronized (a) {
            return a.lockedUntil > now ? a.lockedUntil : 0;
        }
    }

    /** Drop entries that aren't currently locked (e.g. one-off spray attempts). */
    private void prune() {
        long now = clock.getAsLong();
        attempts.values().removeIf(a -> {
            synchronized (a) { return a.lockedUntil <= now; }
        });
    }

    private static String userKey(String username) { return "u:" + username; }
    private static String ipKey(String ip) { return "i:" + ip; }
}
