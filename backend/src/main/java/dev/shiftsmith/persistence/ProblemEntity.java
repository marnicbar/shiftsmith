package dev.shiftsmith.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Single-row table holding the current problem as a JSONB document.
 *
 * <p>The schedule is single-tenant, so there is exactly one row
 * ({@link #SINGLETON_ID}). Hibernate maps {@link #document} to a Postgres
 * {@code jsonb} column using the configured Jackson format mapper.
 */
@Entity
@Table(name = "problem")
public class ProblemEntity extends PanacheEntityBase {

    /** There is only ever one problem; it always lives under this id. */
    public static final Long SINGLETON_ID = 1L;

    @Id
    public Long id;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public ProblemDocument document;

    public Instant updatedAt;
}
