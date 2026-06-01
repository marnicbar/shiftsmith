package dev.shiftsmith.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A recurring shift definition inside a {@link Position} (Positions view).
 * Expanded by the solver into one {@link ShiftAssignment} per slot per occurring day.
 * start/end are minutes-from-midnight (end may be 1440 for "until midnight").
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ShiftTemplate {

    private String id;
    private String name;
    private LocalDate date;
    private int start;
    private int end;
    private Set<String> skills = new HashSet<>();
    private int headcount = 1;
    private String repeat = "weekly";
    private List<String> preferred = new ArrayList<>();
    private LocalDate until;
    private Set<LocalDate> except;

    public ShiftTemplate() {}

    public boolean occursOn(LocalDate d) {
        return Recurrence.occursOn(date, repeat, d, until, except);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public int getStart() { return start; }
    public void setStart(int start) { this.start = start; }

    public int getEnd() { return end; }
    public void setEnd(int end) { this.end = end; }

    public Set<String> getSkills() { return skills; }
    public void setSkills(Set<String> skills) { this.skills = skills; }

    public int getHeadcount() { return headcount; }
    public void setHeadcount(int headcount) { this.headcount = headcount; }

    public String getRepeat() { return repeat; }
    public void setRepeat(String repeat) { this.repeat = repeat; }

    public List<String> getPreferred() { return preferred; }
    public void setPreferred(List<String> preferred) { this.preferred = preferred; }

    public LocalDate getUntil() { return until; }
    public void setUntil(LocalDate until) { this.until = until; }

    public Set<LocalDate> getExcept() { return except; }
    public void setExcept(Set<LocalDate> except) { this.except = except; }
}
