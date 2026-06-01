package dev.shiftsmith.service;

import ai.timefold.solver.core.api.solver.SolverManager;
import ai.timefold.solver.core.api.solver.SolverStatus;
import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.Position;
import dev.shiftsmith.domain.Schedule;
import dev.shiftsmith.domain.Settings;
import dev.shiftsmith.domain.ShiftAssignment;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds the canonical problem (employees, positions, settings, manual overrides)
 * and runs Timefold continuously over the configured horizon.
 *
 * Continuous solving: {@code solveAndListen} streams each new best solution into
 * {@link #bestSolution}. Termination is governed by {@code unimproved-spent-limit}
 * in application.properties, so the solver pauses once the solution is steady.
 * Any change to the problem restarts the solve from the new state.
 */
@ApplicationScoped
public class ScheduleService {

    private static final String JOB_ID = "MAIN";

    @Inject
    SolverManager<Schedule> solverManager;

    private final List<Employee> employees = new ArrayList<>();
    private final List<Position> positions = new ArrayList<>();
    private Settings settings = new Settings("week", 1);
    private Map<String, List<String>> overrides = new HashMap<>();

    private volatile Schedule bestSolution;

    @PostConstruct
    void init() {
        DemoData.seed(employees, positions);
        startSolving();
    }

    // --- problem snapshot ------------------------------------------------

    private Schedule buildProblem() {
        List<ShiftAssignment> assignments = ScheduleExpander.expand(
                positions, employees, settings, overrides, LocalDate.now());
        // Deep-ish copy of employees is unnecessary: the solver only reads them.
        return new Schedule(new ArrayList<>(employees), assignments);
    }

    // --- solver lifecycle ------------------------------------------------

    public synchronized void startSolving() {
        try {
            solverManager.terminateEarly(JOB_ID);
        } catch (Exception ignored) {}
        bestSolution = null;
        Schedule problem = buildProblem();
        // Nothing to solve (no shifts) — keep the empty snapshot as the result.
        if (problem.getAssignments().isEmpty()) {
            bestSolution = problem;
            return;
        }
        solverManager.solveAndListen(JOB_ID, problem, solution -> this.bestSolution = solution);
    }

    public void stopSolving() {
        solverManager.terminateEarly(JOB_ID);
    }

    public SolverStatus status() {
        return solverManager.getSolverStatus(JOB_ID);
    }

    // --- current state ---------------------------------------------------

    public List<Employee> getEmployees() { return employees; }
    public List<Position> getPositions() { return positions; }
    public Settings getSettings() { return settings; }
    public Map<String, List<String>> getOverrides() { return overrides; }

    /**
     * Replace the whole problem from the frontend and re-solve. Null fields are
     * left unchanged so partial syncs (e.g. settings only) are cheap.
     */
    public synchronized void replaceProblem(List<Employee> newEmployees, List<Position> newPositions,
                                            Settings newSettings, Map<String, List<String>> newOverrides) {
        if (newEmployees != null) { employees.clear(); employees.addAll(newEmployees); }
        if (newPositions != null) { positions.clear(); positions.addAll(newPositions); }
        if (newSettings != null) { settings = newSettings; }
        if (newOverrides != null) { overrides = new HashMap<>(newOverrides); }
        startSolving();
    }

    /** Best solved assignments, overlaid on a fresh expansion of the current problem. */
    public synchronized List<ShiftAssignment> currentAssignments() {
        List<ShiftAssignment> fresh = ScheduleExpander.expand(
                positions, employees, settings, overrides, LocalDate.now());
        Schedule best = bestSolution;
        if (best != null && best.getAssignments() != null) {
            Map<String, Employee> solved = new HashMap<>();
            for (ShiftAssignment a : best.getAssignments()) solved.put(a.getId(), a.getEmployee());
            for (ShiftAssignment a : fresh) {
                if (!a.isPinned() && solved.containsKey(a.getId())) a.setEmployee(solved.get(a.getId()));
            }
        }
        return fresh;
    }

    public Schedule getBestSolution() { return bestSolution; }
}
