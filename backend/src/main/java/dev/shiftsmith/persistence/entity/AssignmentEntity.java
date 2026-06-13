package dev.shiftsmith.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One concrete staffing slot (issue #47): the core new table. In this phase it
 * holds only the manual pins migrated from the legacy {@code overrides} map
 * ({@code pinned = true}, {@code source = manual}); a later phase makes the solver
 * upsert its solution here so past rows become the durable schedule and the history
 * the constraints read for boundary lookback.
 */
@Entity
@Table(name = "assignment",
        uniqueConstraints = @UniqueConstraint(name = "uk_assignment_slot",
                columnNames = {"template_id", "occurrence_date", "slot_index"}))
public class AssignmentEntity extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "template_id", nullable = false, length = 255)
    public String templateId;

    @Column(name = "occurrence_date", nullable = false)
    public LocalDate occurrenceDate;

    @Column(name = "slot_index", nullable = false)
    public int slotIndex;

    @Column(name = "start_ts", nullable = false)
    public LocalDateTime startTs;

    @Column(name = "end_ts", nullable = false)
    public LocalDateTime endTs;

    /** Assigned employee, or null when the slot is unstaffed. */
    @Column(name = "employee_id", length = 255)
    public String employeeId;

    @Column(name = "pinned", nullable = false)
    public boolean pinned;

    /** solver | manual */
    @Column(name = "source", nullable = false, length = 16)
    public String source = "solver";

    @Column(name = "solved_at")
    public Instant solvedAt;
}
