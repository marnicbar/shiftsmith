package dev.shiftsmith.domain;

import ai.timefold.solver.core.api.domain.common.PlanningId;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A schedulable person (Personnel view). Identity is the stable {@code id}, so
 * renaming is supported (unlike the original name-keyed model on main).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Employee {

    @PlanningId
    private String id;
    private String firstName;
    private String lastName;
    private String role;
    private int contract;
    private Set<String> skills = new HashSet<>();
    private List<Block> blocks = new ArrayList<>();
    private List<Rule> rules = new ArrayList<>();

    /**
     * Global working-time rules that apply to everyone (from Settings). Injected by
     * the service before solving and used purely as defaults: a global rule only
     * takes effect for a metric+op the employee has no personal rule for. Not part
     * of the employee's persisted state.
     */
    @JsonIgnore
    private List<Rule> globalRules = new ArrayList<>();

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
     * Soft preference, in minutes of the shift {@code [start,end)} on day {@code d}
     * that fall inside a preferred window (counts positive) or an undesired window
     * (counts negative). Availability itself is enforced separately as a hard rule.
     */
    public int preferredMinutes(LocalDate d, int start, int end) {
        return overlapMinutes(mergedRanges(d, b -> "pref".equals(b.getType())), start, end);
    }

    public int undesiredMinutes(LocalDate d, int start, int end) {
        return overlapMinutes(mergedRanges(d, b -> "undes".equals(b.getType())), start, end);
    }

    /**
     * Hard availability: an employee is available only inside the windows defined
     * by their preferred and undesired blocks (an empty calendar means unavailable).
     * A shift may only be assigned if it fits entirely within one such window;
     * adjacent/overlapping blocks merge into a single window.
     */
    public boolean isAvailableFor(LocalDate d, int start, int end) {
        for (int[] w : availableWindows(d)) {
            if (w[0] <= start && end <= w[1]) return true;
        }
        return false;
    }

    /** Merged available windows (preferred ∪ undesired) on day {@code d}. */
    public List<int[]> availableWindows(LocalDate d) {
        return mergedRanges(d, b -> "pref".equals(b.getType()) || "undes".equals(b.getType()));
    }

    /** Minute ranges of matching blocks active on {@code d}, sorted and merged (adjacent ranges join). */
    private List<int[]> mergedRanges(LocalDate d, Predicate<Block> match) {
        List<int[]> raw = new ArrayList<>();
        for (Block b : blocks) {
            if (!match.test(b) || !b.occursOn(d)) continue;
            if (b.isAllDay()) raw.add(new int[]{0, 1440});
            else if (b.getStart() < b.getEnd()) raw.add(new int[]{b.getStart(), b.getEnd()});
        }
        raw.sort(Comparator.comparingInt(r -> r[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] r : raw) {
            int[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && r[0] <= last[1]) {
                last[1] = Math.max(last[1], r[1]);   // overlapping or touching → merge
            } else {
                merged.add(new int[]{r[0], r[1]});
            }
        }
        return merged;
    }

    private static int overlapMinutes(List<int[]> windows, int start, int end) {
        int total = 0;
        for (int[] w : windows) {
            total += Math.max(0, Math.min(end, w[1]) - Math.max(start, w[0]));
        }
        return total;
    }

    // --- Working-time rules (time-varying) ------------------------------

    /**
     * Most restrictive active limit for metric+op at {@code date}, or null if none.
     *
     * <p>An employee's own rules take precedence: a global rule (from Settings) only
     * applies for a metric+op the employee has not defined a personal rule for. Since
     * personal rules can only be <em>stricter</em> than the global one, "personal
     * wins" is equivalent to "tightest wins" for hard limits, while still letting a
     * personal {@code preferred} override the global preference freely.
     */
    public Integer limit(String metric, String op, LocalDate date) {
        Integer personal = restrictiveLimit(rules, metric, op, date);
        if (personal != null) return personal;
        return restrictiveLimit(globalRules, metric, op, date);
    }

    private static Integer restrictiveLimit(List<Rule> rules, String metric, String op, LocalDate date) {
        Integer result = null;
        if (rules == null) return null;
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

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    /** Combined "First Last" for display/logging; empty if neither is set. */
    public String displayName() {
        String f = firstName == null ? "" : firstName.trim();
        String l = lastName == null ? "" : lastName.trim();
        return (f + " " + l).trim();
    }

    /**
     * Backwards-compatibility for problem documents written before the name was
     * split: absorb a single {@code name} by splitting on the last space. Write-only
     * (no matching getter), so it is never serialized back — new documents only carry
     * {@code firstName}/{@code lastName}.
     */
    @JsonProperty("name")
    public void setName(String name) {
        if (name == null || name.isBlank()) return;
        if (firstName != null || lastName != null) return;
        String trimmed = name.trim();
        int i = trimmed.lastIndexOf(' ');
        if (i < 0) {
            firstName = trimmed;
            lastName = "";
        } else {
            firstName = trimmed.substring(0, i).trim();
            lastName = trimmed.substring(i + 1).trim();
        }
    }

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

    @JsonIgnore
    public List<Rule> getGlobalRules() { return globalRules; }
    public void setGlobalRules(List<Rule> globalRules) {
        this.globalRules = globalRules == null ? new ArrayList<>() : globalRules;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Employee e)) return false;
        return Objects.equals(id, e.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }

    @Override
    public String toString() { return displayName() + " (" + id + ")"; }
}
