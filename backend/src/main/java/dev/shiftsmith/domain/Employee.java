package dev.shiftsmith.domain;

import ai.timefold.solver.core.api.domain.common.PlanningId;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A schedulable person (Personnel view). Identity is the stable {@code id}, so
 * renaming is supported (unlike the original name-keyed model on main).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Employee {

    @PlanningId
    private String id;
    private String name;
    private String role;
    private int contract;
    private Set<String> skills = new HashSet<>();
    private List<Block> blocks = new ArrayList<>();
    private List<Rule> rules = new ArrayList<>();

    public Employee() {}

    // --- Availability ---------------------------------------------------

    /** Hard unavailability: a vacation/time-off block covering the day. */
    public boolean isOnVacation(LocalDate d) {
        for (Block b : blocks) {
            if ("vac".equals(b.getType()) && b.coversDay(d)) return true;
        }
        return false;
    }

    /**
     * Soft preference score for working {@code [start,end)} minutes on day {@code d}:
     * +2 per overlapping preferred block, -2 per overlapping undesired block.
     */
    public int preferenceScore(LocalDate d, int start, int end) {
        int sc = 0;
        for (Block b : blocks) {
            if (!b.overlapsMinutes(d, start, end)) continue;
            if ("pref".equals(b.getType())) sc += 2;
            else if ("undes".equals(b.getType())) sc -= 2;
        }
        return sc;
    }

    // --- Working-time rules (time-varying) ------------------------------

    /** Most restrictive active limit for metric+op at {@code date}, or null if none. */
    public Integer limit(String metric, String op, LocalDate date) {
        Integer result = null;
        for (Rule r : rules) {
            Rule.Effective e = r.effectiveAt(date);
            if (!e.active() || !metric.equals(e.metric()) || !op.equals(e.op())) continue;
            if (result == null) {
                result = e.value();
            } else if ("max".equals(op)) {
                result = Math.min(result, e.value());   // tightest ceiling
            } else if ("min".equals(op)) {
                result = Math.max(result, e.value());   // tightest floor
            } else {
                result = e.value();                     // preferred: first wins
            }
        }
        return result;
    }

    public Integer maxLimit(String metric, LocalDate date)   { return limit(metric, "max", date); }
    public Integer minLimit(String metric, LocalDate date)   { return limit(metric, "min", date); }
    public Integer preferred(String metric, LocalDate date)  { return limit(metric, "preferred", date); }

    // --- Accessors ------------------------------------------------------

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public int getContract() { return contract; }
    public void setContract(int contract) { this.contract = contract; }

    public Set<String> getSkills() { return skills; }
    public void setSkills(Set<String> skills) { this.skills = skills; }

    public List<Block> getBlocks() { return blocks; }
    public void setBlocks(List<Block> blocks) { this.blocks = blocks; }

    public List<Rule> getRules() { return rules; }
    public void setRules(List<Rule> rules) { this.rules = rules; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Employee e)) return false;
        return Objects.equals(id, e.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }

    @Override
    public String toString() { return name + " (" + id + ")"; }
}
