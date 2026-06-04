package dev.shiftsmith.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Optional;

/**
 * Transactional persistence gateway for accounts and the signing secret. Mirrors
 * {@code ProblemStore}: the rest of the auth code stays free of JPA concerns.
 */
@ApplicationScoped
public class AuthStore {

    /**
     * Idempotently seed a fresh database: create the default account if no users
     * exist, and store the signing secret if none is present yet. The provided
     * secret is only used when the row is missing — an existing one is kept so
     * tokens stay valid.
     */
    @Transactional
    public void seed(String username, String passwordHash, String secretB64) {
        if (UserAccount.count() == 0) {
            UserAccount u = new UserAccount();
            u.username = username;
            u.passwordHash = passwordHash;
            u.persist();
        }
        AuthConfigEntity cfg = AuthConfigEntity.findById(AuthConfigEntity.SINGLETON_ID);
        if (cfg == null) {
            cfg = new AuthConfigEntity();
            cfg.id = AuthConfigEntity.SINGLETON_ID;
            cfg.secret = secretB64;
            cfg.persist();
        }
    }

    @Transactional
    public Optional<UserAccount> find(String username) {
        return Optional.ofNullable(UserAccount.findByUsername(username));
    }

    @Transactional
    public void updatePassword(String username, String newHash) {
        UserAccount u = UserAccount.findByUsername(username);
        if (u != null) u.passwordHash = newHash;
    }

    @Transactional
    public String secret() {
        AuthConfigEntity cfg = AuthConfigEntity.findById(AuthConfigEntity.SINGLETON_ID);
        return cfg == null ? null : cfg.secret;
    }
}
