package dev.shiftsmith.auth;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * Authentication: seeds the default account on a fresh database, checks
 * credentials, and issues / verifies stateless session tokens.
 *
 * <p>Tokens are HMAC-signed (no server-side session table): a token carries the
 * username and an expiry, signed with a persisted secret, so it survives
 * restarts and lets the SSE stream authenticate via a query parameter. There is
 * no revocation list — changing a password does not invalidate tokens already
 * issued; they simply expire.
 */
@ApplicationScoped
public class AuthService {

    private static final Logger LOG = Logger.getLogger(AuthService.class);

    public static final String DEFAULT_USERNAME = "admin";
    public static final String DEFAULT_PASSWORD = "shiftsmith";

    /** Token lifetimes: short-lived by default, long-lived when "remember me" is on. */
    private static final Duration TTL_DEFAULT = Duration.ofDays(1);
    private static final Duration TTL_REMEMBER = Duration.ofDays(30);

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    @Inject
    AuthStore store;

    /** Cached signing secret, loaded once after seeding. */
    private volatile byte[] secret;

    void onStart(@Observes StartupEvent ev) {
        byte[] fresh = new byte[32];
        new SecureRandom().nextBytes(fresh);
        store.seed(DEFAULT_USERNAME, PasswordHasher.hash(DEFAULT_PASSWORD),
                Base64.getEncoder().encodeToString(fresh));
        secret = Base64.getDecoder().decode(store.secret());
        LOG.info("Auth ready (default account seeded if database was empty)");
    }

    /** Verify credentials and, on success, mint a signed token. */
    public Optional<String> login(String username, String password, boolean remember) {
        Optional<UserAccount> user = store.find(username);
        if (user.isEmpty() || !PasswordHasher.verify(password, user.get().passwordHash)) {
            return Optional.empty();
        }
        return Optional.of(mintToken(username, remember ? TTL_REMEMBER : TTL_DEFAULT));
    }

    /**
     * Change a user's password after confirming the current one.
     *
     * @return true if the current password matched and the change was applied.
     */
    public boolean changePassword(String username, String currentPassword, String newPassword) {
        Optional<UserAccount> user = store.find(username);
        if (user.isEmpty() || !PasswordHasher.verify(currentPassword, user.get().passwordHash)) {
            return false;
        }
        store.updatePassword(username, PasswordHasher.hash(newPassword));
        return true;
    }

    /** Validate a token's signature and expiry, returning the username it carries. */
    public Optional<String> verify(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        int dot = token.indexOf('.');
        if (dot <= 0) return Optional.empty();
        try {
            String payloadB64 = token.substring(0, dot);
            byte[] sig = B64D.decode(token.substring(dot + 1));
            byte[] expectedSig = hmac(payloadB64.getBytes(StandardCharsets.US_ASCII));
            if (!MessageDigest.isEqual(sig, expectedSig)) return Optional.empty();

            String payload = new String(B64D.decode(payloadB64), StandardCharsets.UTF_8);
            int sep = payload.lastIndexOf('\n');
            if (sep < 0) return Optional.empty();
            long expiry = Long.parseLong(payload.substring(sep + 1));
            if (System.currentTimeMillis() > expiry) return Optional.empty();
            return Optional.of(payload.substring(0, sep));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private String mintToken(String username, Duration ttl) {
        long expiry = System.currentTimeMillis() + ttl.toMillis();
        String payload = username + "\n" + expiry;
        String payloadB64 = B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String sig = B64.encodeToString(hmac(payloadB64.getBytes(StandardCharsets.US_ASCII)));
        return payloadB64 + "." + sig;
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign token", e);
        }
    }
}
