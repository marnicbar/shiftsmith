package dev.shiftsmith.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the PBKDF2 password hashing, incl. the raised cost (issue #36). */
class PasswordHasherTest {

    @Test
    void hashRoundTripsAndRejectsAWrongPassword() {
        String stored = PasswordHasher.hash("correct horse battery staple");
        assertThat(PasswordHasher.verify("correct horse battery staple", stored)).isTrue();
        assertThat(PasswordHasher.verify("wrong password", stored)).isFalse();
    }

    @Test
    void newHashesUseTheRaisedIterationCount() {
        String stored = PasswordHasher.hash("pw");
        // Self-describing format: pbkdf2$<iterations>$<salt>$<hash>
        assertThat(stored).startsWith("pbkdf2$600000$");
    }

    @Test
    void verifyHonoursTheIterationCountEmbeddedInTheHash() {
        // A hash written at an older cost still validates (the count travels with the hash),
        // so raising ITERATIONS doesn't lock existing users out before they rotate.
        String legacy = "pbkdf2$210000$" + saltAndKeyFor("legacy-pw", 210_000);
        assertThat(PasswordHasher.verify("legacy-pw", legacy)).isTrue();
        assertThat(PasswordHasher.verify("nope", legacy)).isFalse();
    }

    @Test
    void malformedHashesAreRejected() {
        assertThat(PasswordHasher.verify("x", null)).isFalse();
        assertThat(PasswordHasher.verify("x", "not-a-hash")).isFalse();
        assertThat(PasswordHasher.verify("x", "pbkdf2$abc$def$ghi")).isFalse();
    }

    /** Build the "<salt>$<hash>" tail of a stored hash at a given cost, via the public API. */
    private static String saltAndKeyFor(String password, int iterations) {
        // Reuse the production hasher at the current cost, then transplant the salt/key into a
        // legacy-iteration string would mismatch; instead derive directly here for the test.
        try {
            byte[] salt = new byte[16];
            new java.security.SecureRandom().nextBytes(salt);
            javax.crypto.spec.PBEKeySpec spec =
                    new javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            byte[] dk = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            java.util.Base64.Encoder b64 = java.util.Base64.getEncoder();
            return b64.encodeToString(salt) + "$" + b64.encodeToString(dk);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
