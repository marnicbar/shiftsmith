package dev.shiftsmith.domain;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.entity.PlanningPin;
import ai.timefold.solver.core.api.domain.common.PlanningId;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One concrete staffing slot: a single occurrence of a {@link ShiftTemplate}
 * on a specific {@code date}, for one of its {@code headcount} positions.
 * The planning variable {@code employee} may be left unassigned (understaffed).
 *
 * When the user manually pins a person to a slot in the UI, {@code pinned} is
 * set and the solver keeps that assignment fixed.
 */
@PlanningEntity
@JsonIgnoreProperties(ignoreUnknown = true)
public class ShiftAssignment {

    @PlanningId
    private String id;

    private String positionId;
    private String positionName;
    private String shiftTemplateId;
    private String shiftName;
    private int slotIndex;

    private LocalDate date;
    private LocalDateTime start;
    private LocalDateTime end;
    private Set<String> requiredSkills;
    private List<String> preferredEmployeeIds;

    @PlanningPin
    private boolean pinned;

    /**
     * A worked shift from before the solve window, loaded as a fixed fact so the
     * boundary constraints (rest, consecutive days, weekly/monthly hours) see real
     * history (issue #47, Phase 2). History slots are always pinned; per-shift rules,
     * coverage and preferences ignore them — only the aggregate/rest/consec
     * calculations count them, and only where a window slot is involved.
     */
    private boolean history;

    @PlanningVariable(allowsUnassigned = true)
    private Employee employee;

    public ShiftAssignment() {}

    public ShiftAssignment(String id, Position position, ShiftTemplate template, int slotIndex,
                           LocalDate date, LocalDateTime start, LocalDateTime end) {
        this.id = id;
        this.positionId = position.getId();
        this.positionName = position.getName();
        this.shiftTemplateId = template.getId();
        this.shiftName = template.getName();
        this.slotIndex = slotIndex;
        this.date = date;
        this.start = start;
        this.end = end;
        this.requiredSkills = template.getSkills();
        this.preferredEmployeeIds = template.getPreferred();
    }

    /** Slot duration in (fractional) hours, used by the hours-limit constraints. */
    @JsonIgnore
    public double getDurationHours() {
        return java.time.Duration.between(start, end).toMinutes() / 60.0;
    }

    @JsonIgnore
    public LocalDate getWeekStart() {
        return date.with(java.time.DayOfWeek.MONDAY);
    }

    @JsonIgnore
    public LocalDate getMonthStart() {
        return date.withDayOfMonth(1);
    }

    @JsonIgnore
    public int getStartMinutes() {
        return start.getHour() * 60 + start.getMinute();
    }

    @JsonIgnore
    public int getEndMinutes() {
        // An overnight shift's end lands on a later calendar day; express it as
        // minutes past this slot's own midnight so it stays after the start. A
        // 22:00–24:00 shift reports 1440, a 22:00–02:00 shift reports 1560 — both
        // consistent with how availability windows wrap an overnight block.
        int dayOffset = (int) (end.toLocalDate().toEpochDay() - date.toEpochDay());
        return dayOffset * 1440 + end.getHour() * 60 + end.getMinute();
    }

    public boolean isPreferred(Employee e) {
        return e != null && preferredEmployeeIds != null && preferredEmployeeIds.contains(e.getId());
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getPositionId() { return positionId; }
    public void setPositionId(String positionId) { this.positionId = positionId; }

    public String getPositionName() { return positionName; }
    public void setPositionName(String positionName) { this.positionName = positionName; }

    public String getShiftTemplateId() { return shiftTemplateId; }
    public void setShiftTemplateId(String shiftTemplateId) { this.shiftTemplateId = shiftTemplateId; }

    public String getShiftName() { return shiftName; }
    public void setShiftName(String shiftName) { this.shiftName = shiftName; }

    public int getSlotIndex() { return slotIndex; }
    public void setSlotIndex(int slotIndex) { this.slotIndex = slotIndex; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public LocalDateTime getStart() { return start; }
    public void setStart(LocalDateTime start) { this.start = start; }

    public LocalDateTime getEnd() { return end; }
    public void setEnd(LocalDateTime end) { this.end = end; }

    public Set<String> getRequiredSkills() { return requiredSkills; }
    public void setRequiredSkills(Set<String> requiredSkills) { this.requiredSkills = requiredSkills; }

    public List<String> getPreferredEmployeeIds() { return preferredEmployeeIds; }
    public void setPreferredEmployeeIds(List<String> preferredEmployeeIds) { this.preferredEmployeeIds = preferredEmployeeIds; }

    public boolean isPinned() { return pinned; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }

    public boolean isHistory() { return history; }
    public void setHistory(boolean history) { this.history = history; }

    public Employee getEmployee() { return employee; }
    public void setEmployee(Employee employee) { this.employee = employee; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ShiftAssignment s)) return false;
        return Objects.equals(id, s.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }
}
