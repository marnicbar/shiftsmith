package dev.shiftsmith.persistence.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.time.Instant;

/**
 * Common bookkeeping columns shared by every normalized entity table (issue #47):
 * a {@code version} for optimistic concurrency (enforced by the granular per-resource
 * stores) and {@code created_at}/{@code updated_at} audit timestamps.
 */
@MappedSuperclass
public abstract class TimestampedEntity extends PanacheEntityBase {

    /**
     * Optimistic-concurrency counter. A plain column rather than a Hibernate
     * {@code @Version}: the granular per-resource stores enforce it themselves —
     * comparing the caller's expected version and bumping on each successful write —
     * so the conditional-request ({@code If-Match}/{@code ETag} → 409) logic lives in
     * one place instead of relying on a Hibernate {@code OptimisticLockException}.
     */
    @Column(name = "version", nullable = false)
    public long version = 0;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
