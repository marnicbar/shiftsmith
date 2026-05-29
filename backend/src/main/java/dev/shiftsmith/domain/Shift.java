package dev.shiftsmith.domain;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

@PlanningEntity
public class Shift {

    @PlanningId
    private String id;
    private LocalDateTime start;
    private LocalDateTime end;
    private String location;
    private String requiredSkill;

    @PlanningVariable
    private Employee employee;

    public Shift() {}

    public Shift(String id, LocalDateTime start, LocalDateTime end, String location, String requiredSkill) {
        this.id = id;
        this.start = start;
        this.end = end;
        this.location = location;
        this.requiredSkill = requiredSkill;
    }

    public boolean isOverlappingWithDate(LocalDate date) {
        return !date.isBefore(start.toLocalDate()) && !date.isAfter(end.toLocalDate());
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public LocalDateTime getStart() { return start; }
    public void setStart(LocalDateTime start) { this.start = start; }

    public LocalDateTime getEnd() { return end; }
    public void setEnd(LocalDateTime end) { this.end = end; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getRequiredSkill() { return requiredSkill; }
    public void setRequiredSkill(String requiredSkill) { this.requiredSkill = requiredSkill; }

    public Employee getEmployee() { return employee; }
    public void setEmployee(Employee employee) { this.employee = employee; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Shift s)) return false;
        return Objects.equals(id, s.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }

    @Override
    public String toString() { return id; }
}
