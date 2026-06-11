package dev.shiftsmith.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.time.LocalDate;

/**
 * A date-scheduled modification to a {@link WorkRuleEntity} (issue #47), replacing
 * {@code Change}. Retained as an append-only log row and resolved per-date exactly
 * as {@code Rule.effectiveAt} does — never collapsed into the base rule (see the
 * implementation note on the issue).
 */
@Embeddable
public class WorkRuleChangeEmbeddable {

    @Column(name = "change_id", length = 255)
    public String id;

    @Column(name = "effective_date")
    public LocalDate effectiveDate;

    /** set | remove */
    @Column(name = "kind", length = 16)
    public String kind;

    @Column(name = "metric", length = 16)
    public String metric;

    @Column(name = "op", length = 16)
    public String op;

    @Column(name = "value", nullable = false)
    public int value;

    public WorkRuleChangeEmbeddable() {}
}
