package dev.shiftsmith.domain;

import ai.timefold.solver.core.api.domain.lookup.PlanningId;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public class Employee {

    @PlanningId
    private String name;
    private Set<String> skills = new HashSet<>();
    private Set<LocalDate> unavailableDates = new HashSet<>();
    private Set<LocalDate> undesiredDates = new HashSet<>();
    private Set<LocalDate> desiredDates = new HashSet<>();

    public Employee() {}

    public Employee(String name, Set<String> skills) {
        this.name = name;
        this.skills = skills;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Set<String> getSkills() { return skills; }
    public void setSkills(Set<String> skills) { this.skills = skills; }

    public Set<LocalDate> getUnavailableDates() { return unavailableDates; }
    public void setUnavailableDates(Set<LocalDate> unavailableDates) { this.unavailableDates = unavailableDates; }

    public Set<LocalDate> getUndesiredDates() { return undesiredDates; }
    public void setUndesiredDates(Set<LocalDate> undesiredDates) { this.undesiredDates = undesiredDates; }

    public Set<LocalDate> getDesiredDates() { return desiredDates; }
    public void setDesiredDates(Set<LocalDate> desiredDates) { this.desiredDates = desiredDates; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Employee e)) return false;
        return Objects.equals(name, e.name);
    }

    @Override
    public int hashCode() { return Objects.hashCode(name); }

    @Override
    public String toString() { return name; }
}
