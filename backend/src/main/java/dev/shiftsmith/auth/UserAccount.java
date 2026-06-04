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

    public static UserAccount findByUsername(String username) {
        return find("username", username).firstResult();
    }
}
