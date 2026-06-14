package dev.shiftsmith.auth;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/**
 * Authentication: seeds the default account on a fresh database, checks
 * credentials, and issues / verifies stateless session tokens.
 *
 * <p>Tokens are HMAC-signed (no server-side session table): a token carries the
 * username, a fingerprint of the account's password hash, and an expiry, signed
 * with a persisted secret, so it survives restarts and lets the SSE stream
 * authenticate via a query parameter. The fingerprint gives stateless
 * revocation: {@link #verify} re-checks it against the account's current password
 * hash, so changing a password invalidates every token minted before the change
 * (older tokens otherwise simply expire).
 */
@ApplicationScoped
public class AuthService {

    private static final Logger LOG = Logger.getLogger(AuthService.class);

    public static final String DEFAULT_USERNAME = "admin";
    public static final String DEFAULT_PASSWORD = "shiftsmith";

    /**
     * Initial admin credentials for a fresh database. When the password is
     * supplied (via {@code SHIFTSMITH_ADMIN_PASSWORD}) the operator has chosen
     * it, so the account is ready to use. When it is absent we fall back to the
     * publicly-known {@link #DEFAULT_PASSWORD} but flag the account so the API
     * refuses to serve it until the password is rotated.
     */
    @ConfigProperty(name = "shiftsmith.admin.username", defaultValue = DEFAULT_USERNAME)
    String initialUsername;

    @ConfigProperty(name = "shiftsmith.admin.password")
    Optional<String> initialPassword;

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

        String username = (initialUsername == null || initialUsername.isBlank())
                ? DEFAULT_USERNAME : initialUsername.trim();
        boolean operatorChosen = initialPassword.map(p -> !p.isBlank()).orElse(false);
        String password = operatorChosen ? initialPassword.get() : DEFAULT_PASSWORD;

        store.seed(username, PasswordHasher.hash(password),
                Base64.getEncoder().encodeToString(fresh), !operatorChosen);
        secret = Base64.getDecoder().decode(store.secret());

        if (!operatorChosen && store.mustChangePassword(username)) {
            LOG.warnf("Auth ready, but account '%s' is still using the publicly-known default password. "
                    + "Protected endpoints are blocked until it is changed (or set SHIFTSMITH_ADMIN_PASSWORD "
                    + "to provision a password at deploy time).", username);
        } else {
            LOG.info("Auth ready (default account seeded if database was empty)");
        }
    }

    /** Whether the user still has to rotate a seeded, publicly-known password. */
    public boolean mustChangePassword(String username) {
        return store.mustChangePassword(username);
    }

    /** Verify credentials and, on success, mint a signed token. */
    public Optional<String> login(String username, String password, boolean remember) {
        Optional<UserAccount> user = store.find(username);
        if (user.isEmpty() || !PasswordHasher.verify(password, user.get().passwordHash)) {
            return Optional.empty();
        }
        return Optional.of(mintToken(username, user.get().passwordHash,
                remember ? TTL_REMEMBER : TTL_DEFAULT));
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

    /** The account for a username (its role + linked employee), or empty. */
    public Optional<UserAccount> account(String username) {
        return store.find(username);
    }

    /**
     * Provision a {@code manager}/{@code employee} account (issue #47, Phase 6). An
     * employee account is linked to the person it represents via {@code employeeId}.
     */
    public boolean createUser(String username, String password, String role, String employeeId) {
        return store.createUser(username, PasswordHasher.hash(password), role, employeeId);
    }

    /**
     * Validate a token's signature, password-hash fingerprint and expiry, returning
     * the username it carries. The fingerprint check means a token is only honored
     * while it still matches the account's current password hash, so a password
     * change (or a vanished account) invalidates it.
     */
    public Optional<String> verify(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        int dot = token.indexOf('.');
        if (dot <= 0) return Optional.empty();
        try {
            String payloadB64 = token.substring(0, dot);
            byte[] sig = B64D.decode(token.substring(dot + 1));
            byte[] expectedSig = hmac(payloadB64.getBytes(StandardCharsets.US_ASCII));
            if (!MessageDigest.isEqual(sig, expectedSig)) return Optional.empty();

            // Payload is "username\nfingerprint\nexpiry"; parse from the end so a
            // username containing a newline can't shift the fixed trailing fields.
            String payload = new String(B64D.decode(payloadB64), StandardCharsets.UTF_8);
            int lastNl = payload.lastIndexOf('\n');
            if (lastNl < 0) return Optional.empty();
            int prevNl = payload.lastIndexOf('\n', lastNl - 1);
            if (prevNl < 0) return Optional.empty();
            String username = payload.substring(0, prevNl);
            String fingerprint = payload.substring(prevNl + 1, lastNl);
            long expiry = Long.parseLong(payload.substring(lastNl + 1));
            if (System.currentTimeMillis() > expiry) return Optional.empty();

            // Revocation: the token must still match the account's current password
            // hash. Compared in constant time, like the signature above.
            Optional<UserAccount> user = store.find(username);
            if (user.isEmpty()) return Optional.empty();
            byte[] presented = fingerprint.getBytes(StandardCharsets.US_ASCII);
            byte[] expected = fingerprint(user.get().passwordHash).getBytes(StandardCharsets.US_ASCII);
            if (!MessageDigest.isEqual(presented, expected)) return Optional.empty();
            return Optional.of(username);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private String mintToken(String username, String passwordHash, Duration ttl) {
        long expiry = System.currentTimeMillis() + ttl.toMillis();
        String payload = username + "\n" + fingerprint(passwordHash) + "\n" + expiry;
        String payloadB64 = B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String sig = B64.encodeToString(hmac(payloadB64.getBytes(StandardCharsets.US_ASCII)));
        return payloadB64 + "." + sig;
    }

    /**
     * A short, deterministic tag derived from the account's current password hash.
     * Embedded in the token and re-checked on {@link #verify}: because the hash
     * changes whenever the password does, this invalidates every token minted
     * before the change — stateless revocation without a session table.
     */
    private String fingerprint(String passwordHash) {
        byte[] tag = hmac(("pw:" + passwordHash).getBytes(StandardCharsets.UTF_8));
        return B64.encodeToString(Arrays.copyOf(tag, 12));
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
