package dev.shiftsmith.persistence.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A recurring shift definition inside a position (issue #47), replacing
 * {@code ShiftTemplate}. Interval-queryable like {@code availability_block}.
 * Required skills, skipped occurrences and the ordered preferred-employee list are
 * normalized into companion tables; {@code days} holds weekly-on-selected-days.
 */
@Entity
@Table(name = "shift_template")
public class ShiftTemplateEntity extends TimestampedEntity {

    @Id
    @Column(name = "id", length = 255)
    public String id;

    @Column(name = "position_id", nullable = false, length = 255)
    public String positionId;

    @Column(name = "name")
    public String name;

    @Column(name = "anchor_date")
    public LocalDate anchorDate;

    @Column(name = "start_min", nullable = false)
    public int startMin;

    @Column(name = "end_min", nullable = false)
    public int endMin;

    @Column(name = "headcount", nullable = false)
    public int headcount = 1;

    @Column(name = "repeat", nullable = false, length = 16)
    public String repeat = "weekly";

    @Column(name = "until_date")
    public LocalDate untilDate;

    /** Weekly-on-selected-days (Mon=0 … Sun=6), or null. */
    @Column(name = "days")
    public Integer[] days;

    @ElementCollection
    @CollectionTable(name = "shift_template_skill", joinColumns = @JoinColumn(name = "template_id"))
    @Column(name = "skill")
    public Set<String> skills = new HashSet<>();

    @ElementCollection
    @CollectionTable(name = "shift_template_exception", joinColumns = @JoinColumn(name = "template_id"))
    @Column(name = "exception_date")
    public Set<LocalDate> exceptions = new HashSet<>();

    /** Ordered preferred-employee ids. */
    @ElementCollection
    @CollectionTable(name = "shift_template_preferred", joinColumns = @JoinColumn(name = "template_id"))
    @OrderColumn(name = "ordinal")
    @Column(name = "employee_id")
    public List<String> preferred = new ArrayList<>();
}
