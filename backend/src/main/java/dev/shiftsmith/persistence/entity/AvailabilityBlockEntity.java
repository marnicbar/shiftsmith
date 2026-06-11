package dev.shiftsmith.persistence.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * A calendar item on an employee's availability calendar (issue #47), replacing
 * {@code Block}. Stored as a first-class, interval-queryable row: a block is
 * relevant to {@code [from,to]} when its recurrence projects into the range or its
 * {@code [anchor_date, end_date]} span overlaps it (see the index on
 * {@code (employee_id, anchor_date)}). Skipped occurrences live in
 * {@code availability_block_exception}.
 */
@Entity
@Table(name = "availability_block")
public class AvailabilityBlockEntity extends TimestampedEntity {

    @Id
    @Column(name = "id", length = 255)
    public String id;

    @Column(name = "employee_id", nullable = false, length = 255)
    public String employeeId;

    /** pref | undes | vac */
    @Column(name = "type", nullable = false, length = 16)
    public String type;

    @Column(name = "anchor_date")
    public LocalDate anchorDate;

    @Column(name = "start_min", nullable = false)
    public int startMin;

    @Column(name = "end_min", nullable = false)
    public int endMin;

    @Column(name = "all_day", nullable = false)
    public boolean allDay;

    /** none | daily | weekly */
    @Column(name = "repeat", nullable = false, length = 16)
    public String repeat = "none";

    @Column(name = "until_date")
    public LocalDate untilDate;

    /** Multi-day span (inclusive); used e.g. for a vacation range. */
    @Column(name = "end_date")
    public LocalDate endDate;

    /** Weekly-on-selected-days (Mon=0 … Sun=6), or null. */
    @Column(name = "days")
    public Integer[] days;

    @ElementCollection
    @CollectionTable(name = "availability_block_exception", joinColumns = @JoinColumn(name = "block_id"))
    @Column(name = "exception_date")
    public Set<LocalDate> exceptions = new HashSet<>();
}
