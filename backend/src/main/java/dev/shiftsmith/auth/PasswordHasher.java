package dev.shiftsmith.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Salted PBKDF2 password hashing using only the JDK — no external crypto
 * dependency. Hashes are stored as {@code pbkdf2$<iterations>$<saltB64>$<hashB64>}
 * so the parameters travel with the hash and can evolve over time.
 */
public final class PasswordHasher {

    private static final String ALGO = "PBKDF2WithHmacSHA256";
    // OWASP guidance for PBKDF2-HMAC-SHA256 (issue #36). The self-describing hash format
    // carries the iteration count, so {@link #verify} keeps validating older hashes while
    // new/changed passwords are written at this cost.
    private static final int ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getEncoder();
    private static final Base64.Decoder B64D = Base64.getDecoder();

    private PasswordHasher() {}

    /** Hash a plain-text password with a fresh random salt. */
    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] dk = pbkdf2(password, salt, ITERATIONS, KEY_BITS);
        return "pbkdf2$" + ITERATIONS + "$" + B64.encodeToString(salt) + "$" + B64.encodeToString(dk);
    }

    /** Verify a plain-text password against a stored hash, in constant time. */
    public static boolean verify(String password, String stored) {
        if (stored == null) return false;
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !"pbkdf2".equals(parts[0])) return false;
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = B64D.decode(parts[2]);
            byte[] expected = B64D.decode(parts[3]);
            byte[] actual = pbkdf2(password, salt, iterations, expected.length * 8);
            return constantTimeEquals(expected, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations, int keyBits) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, keyBits);
            return SecretKeyFactory.getInstance(ALGO).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash password", e);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }
}
