package dev.shiftsmith.domain;

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.solver.SolverStatus;

import java.util.List;

@PlanningSolution
public class EmployeeSchedule {

    @ProblemFactCollectionProperty
    @ValueRangeProvider
    private List<Employee> employees;

    @PlanningEntityCollectionProperty
    private List<Shift> shifts;

    @PlanningScore
    private HardSoftScore score;

    private SolverStatus solverStatus;

    public EmployeeSchedule() {}

    public EmployeeSchedule(List<Employee> employees, List<Shift> shifts) {
        this.employees = employees;
        this.shifts = shifts;
    }

    public List<Employee> getEmployees() { return employees; }
    public void setEmployees(List<Employee> employees) { this.employees = employees; }

    public List<Shift> getShifts() { return shifts; }
    public void setShifts(List<Shift> shifts) { this.shifts = shifts; }

    public HardSoftScore getScore() { return score; }
    public void setScore(HardSoftScore score) { this.score = score; }

    public SolverStatus getSolverStatus() { return solverStatus; }
    public void setSolverStatus(SolverStatus solverStatus) { this.solverStatus = solverStatus; }
}
