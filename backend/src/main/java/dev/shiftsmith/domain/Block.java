package dev.shiftsmith.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.util.Set;

/**
 * A calendar item on an employee's availability calendar (Personnel view).
 * type: "pref" (preferred), "undes" (undesired) or "vac" (vacation / time off).
 * start/end are minutes-from-midnight; allDay blocks ignore them.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Block {

    private String id;
    private String type;
    private LocalDate date;
    private int start;
    private int end;
    private boolean allDay;
    private String repeat = "none";
    private LocalDate until;
    private Set<LocalDate> except;

    public Block() {}

    public boolean occursOn(LocalDate d) {
        return Recurrence.occursOn(date, repeat, d, until, except);
    }

    /** True when this block covers the given calendar day at all (any time). */
    public boolean coversDay(LocalDate d) {
        return occursOn(d);
    }

    /** True when this timed block overlaps the [shiftStart, shiftEnd) minute range on day d. */
    public boolean overlapsMinutes(LocalDate d, int shiftStart, int shiftEnd) {
        if (allDay || !occursOn(d)) return false;
        return start < shiftEnd && end > shiftStart;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public int getStart() { return start; }
    public void setStart(int start) { this.start = start; }

    public int getEnd() { return end; }
    public void setEnd(int end) { this.end = end; }

    public boolean isAllDay() { return allDay; }
    public void setAllDay(boolean allDay) { this.allDay = allDay; }

    public String getRepeat() { return repeat; }
    public void setRepeat(String repeat) { this.repeat = repeat; }

    public LocalDate getUntil() { return until; }
    public void setUntil(LocalDate until) { this.until = until; }

    public Set<LocalDate> getExcept() { return except; }
    public void setExcept(Set<LocalDate> except) { this.except = except; }
}
