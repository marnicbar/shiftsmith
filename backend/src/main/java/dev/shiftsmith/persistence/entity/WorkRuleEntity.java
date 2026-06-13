package dev.shiftsmith.persistence.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * A working-time rule (issue #47), replacing {@code Rule}. A {@code null}
 * {@code employee_id} marks a global rule (replacing {@code Settings.globalRules});
 * otherwise the rule is personal to that employee. The base metric/op/value plus an
 * ordered, retained log of {@link WorkRuleChangeEmbeddable} changes.
 */
@Entity
@Table(name = "work_rule")
public class WorkRuleEntity extends TimestampedEntity {

    @Id
    @Column(name = "id", length = 255)
    public String id;

    /** Owning employee, or null for a global rule. */
    @Column(name = "employee_id", length = 255)
    public String employeeId;

    /** dayHours | weekHours | monthHours | consecDays | restHours */
    @Column(name = "metric", nullable = false, length = 16)
    public String metric;

    /** preferred | min | max */
    @Column(name = "op", nullable = false, length = 16)
    public String op;

    @Column(name = "value", nullable = false)
    public int value;

    @ElementCollection
    @CollectionTable(name = "work_rule_change", joinColumns = @JoinColumn(name = "rule_id"))
    @OrderColumn(name = "ordinal")
    public List<WorkRuleChangeEmbeddable> changes = new ArrayList<>();
}
