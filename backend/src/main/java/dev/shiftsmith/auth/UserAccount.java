package dev.shiftsmith.auth;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A login account. Today there is exactly one ({@code admin}, seeded on a fresh
 * database), but the table is keyed by a unique username so it can grow into
 * per-employee accounts later without a schema change.
 */
@Entity
@Table(name = "app_user")
public class UserAccount extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public Long id;

    @Column(unique = true, nullable = false)
    public String username;

    /** PBKDF2 hash, see {@link PasswordHasher}. */
    @Column(nullable = false)
    public String passwordHash;

    /**
     * When set, the operator is still using a publicly-known seeded password and
     * must rotate it before any protected endpoint will serve them. Nullable so
     * the column can be added to an existing table without a backfill; a missing
     * value is treated as "no change required".
     */
    public Boolean mustChangePassword;

    public boolean mustChange() {
        return Boolean.TRUE.equals(mustChangePassword);
    }

    public static UserAccount findByUsername(String username) {
        return find("username", username).firstResult();
    }
}
