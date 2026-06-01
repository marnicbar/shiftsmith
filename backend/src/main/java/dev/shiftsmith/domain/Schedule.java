package dev.shiftsmith.domain;

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.SolverStatus;

import java.util.List;

/**
 * The planning problem handed to Timefold: assign {@link Employee}s to the
 * expanded {@link ShiftAssignment} slots.
 *
 * Score levels (lexicographic):
 *   hard   — must never be violated (skills, rest, hour limits, overlaps, vacation)
 *   medium — coverage: fill as many slots as possible
 *   soft   — optimise preferences, balance and fairness
 */
@PlanningSolution
public class Schedule {

    @ProblemFactCollectionProperty
    @ValueRangeProvider
    private List<Employee> employees;

    @PlanningEntityCollectionProperty
    private List<ShiftAssignment> assignments;

    @PlanningScore
    private HardMediumSoftScore score;

    private SolverStatus solverStatus;

    public Schedule() {}

    public Schedule(List<Employee> employees, List<ShiftAssignment> assignments) {
        this.employees = employees;
        this.assignments = assignments;
    }

    public List<Employee> getEmployees() { return employees; }
    public void setEmployees(List<Employee> employees) { this.employees = employees; }

    public List<ShiftAssignment> getAssignments() { return assignments; }
    public void setAssignments(List<ShiftAssignment> assignments) { this.assignments = assignments; }

    public HardMediumSoftScore getScore() { return score; }
    public void setScore(HardMediumSoftScore score) { this.score = score; }

    public SolverStatus getSolverStatus() { return solverStatus; }
    public void setSolverStatus(SolverStatus solverStatus) { this.solverStatus = solverStatus; }
}
