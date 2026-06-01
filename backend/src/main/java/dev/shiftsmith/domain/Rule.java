package dev.shiftsmith.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A working-time requirement on an employee.
 *   metric: dayHours | weekHours | monthHours | consecDays | restHours
 *   op:     preferred (soft) | min (hard, "at least") | max (hard, "at most")
 * {@code changes} schedule future modifications; {@link #effectiveAt} resolves
 * the rule as it stands on a given date.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Rule {

    /** Snapshot of a rule's metric/op/value as it applies on a particular date. */
    public record Effective(boolean active, String metric, String op, int value) {
        static final Effective INACTIVE = new Effective(false, null, null, 0);
    }

    private String id;
    private String metric;
    private String op;
    private int value;
    private List<Change> changes = new ArrayList<>();

    public Rule() {}

    /**
     * Resolve this rule on {@code date}, applying the latest scheduled change
     * with {@code change.date <= date}. A "remove" change deactivates the rule.
     */
    public Effective effectiveAt(LocalDate date) {
        String m = metric, o = op;
        int v = value;
        boolean active = true;
        if (changes != null) {
            List<Change> applicable = new ArrayList<>();
            for (Change c : changes) {
                if (c.getDate() != null && !c.getDate().isAfter(date)) applicable.add(c);
            }
            applicable.sort((a, b) -> a.getDate().compareTo(b.getDate()));
            for (Change c : applicable) {
                if ("remove".equals(c.getKind())) {
                    active = false;
                } else { // "set"
                    active = true;
                    if (c.getMetric() != null) m = c.getMetric();
                    if (c.getOp() != null) o = c.getOp();
                    v = c.getValue();
                }
            }
        }
        return active ? new Effective(true, m, o, v) : Effective.INACTIVE;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }

    public String getOp() { return op; }
    public void setOp(String op) { this.op = op; }

    public int getValue() { return value; }
    public void setValue(int value) { this.value = value; }

    public List<Change> getChanges() { return changes; }
    public void setChanges(List<Change> changes) { this.changes = changes; }
}
