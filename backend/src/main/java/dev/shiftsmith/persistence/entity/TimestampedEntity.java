package dev.shiftsmith.persistence.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.time.Instant;

/**
 * Common bookkeeping columns shared by every normalized entity table (issue #47):
 * a {@code version} for optimistic concurrency (wired now, enforced in the
 * granular-write phase) and {@code created_at}/{@code updated_at} audit timestamps.
 */
@MappedSuperclass
public abstract class TimestampedEntity extends PanacheEntityBase {

    /**
     * Optimistic-concurrency counter. Intentionally a plain column (not {@code @Version})
     * in this phase: the persistence path still rewrites the whole document, so enabling
     * Hibernate's optimistic locking now would add no value. The granular per-resource
     * writes promote this to {@code @Version}.
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
