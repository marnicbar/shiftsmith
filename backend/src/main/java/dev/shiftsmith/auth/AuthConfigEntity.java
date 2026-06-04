package dev.shiftsmith.auth;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Single-row table holding the server's HMAC signing secret. Persisting it (as
 * opposed to generating a fresh one each boot) keeps "remember me" tokens valid
 * across restarts.
 */
@Entity
@Table(name = "auth_config")
public class AuthConfigEntity extends PanacheEntityBase {

    public static final Long SINGLETON_ID = 1L;

    @Id
    public Long id;

    /** Base64-encoded random secret used to sign session tokens. */
    @Column(nullable = false, length = 512)
    public String secret;
}
