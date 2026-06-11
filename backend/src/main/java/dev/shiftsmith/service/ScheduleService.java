package dev.shiftsmith.service;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.SolverManager;
import ai.timefold.solver.core.api.solver.SolverStatus;
import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.Position;
import dev.shiftsmith.domain.Schedule;
import dev.shiftsmith.domain.Settings;
import dev.shiftsmith.domain.ShiftAssignment;
import dev.shiftsmith.persistence.PersistFailedException;
import dev.shiftsmith.persistence.ProblemDocument;
import dev.shiftsmith.persistence.ProblemStore;
import dev.shiftsmith.realtime.ScheduleBroadcaster;
import dev.shiftsmith.rest.dto.ScheduleDTO;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Holds the canonical problem (employees, positions, settings, manual overrides)
 * and runs Timefold continuously over the configured horizon.
 *
 * <p>The problem is persisted to the database as a JSONB document so it survives
 * restarts; on boot we rehydrate from it (starting from an empty problem on a
 * fresh database). Every change — a new best solution from the solver, a problem
 * edit, or a solver start/stop — pushes a tick to {@link ScheduleBroadcaster}, so
 * connected browsers get live updates over SSE instead of polling.
 *
 * <p>Continuous solving: {@code solveBuilder().run()} streams each new best
 * solution into {@link #bestSolution}. Termination is governed by
 * {@code unimproved-spent-limit} in application.properties, so the solver pauses
 * once the solution is steady. Any change to the problem restarts the solve.
 */
@ApplicationScoped
public class ScheduleService {

    private static final Logger LOG = Logger.getLogger(ScheduleService.class);
    private static final String JOB_ID = "MAIN";

    @Inject
    SolverManager<Schedule> solverManager;

    @Inject
    ProblemStore store;

    @Inject
    ScheduleBroadcaster broadcaster;

    private final List<Employee> employees = new ArrayList<>();
    private final List<Position> positions = new ArrayList<>();
    private Settings settings = new Settings("week", 1);
    private Map<String, List<String>> overrides = new HashMap<>();

    private volatile Schedule bestSolution;

    /** Load the persisted problem (empty on a fresh database) and start solving at boot. */
    void onStart(@Observes StartupEvent ev) {
        Optional<ProblemDocument> saved = store.load();
        if (saved.isPresent()) {
            ProblemDocument d = saved.get();
            if (d.employees != null) { employees.addAll(d.employees); }
            if (d.positions != null) { positions.addAll(d.positions); }
            if (d.settings != null) { settings = d.settings; }
            if (d.overrides != null) { overrides = new HashMap<>(d.overrides); }
            LOG.infof("Loaded problem from database: %d employees, %d positions",
                    employees.size(), positions.size());
        } else {
            // Fresh database: start empty (no demo data). Persist the empty baseline,
            // but never let a write failure abort startup — we serve the in-memory
            // state and the first successful edit persists it.
            try { persist(snapshot()); }
            catch (Exception e) { LOG.error("Could not persist initial empty problem", e); }
            LOG.info("Fresh database — starting with an empty problem");
        }
        // A document persisted before validation existed could still be poison. Never
        // let it abort startup: serve the loaded-but-unsolved state and let the next
        // valid edit fix it, instead of bricking every boot.
        try {
            startSolving();
        } catch (Exception e) {
            LOG.error("Loaded problem could not be solved at startup; serving it unsolved", e);
        }
    }

    // --- problem snapshot ------------------------------------------------

    private Schedule buildProblem() {
        return buildProblem(employees, positions, settings, overrides);
    }

    private static Schedule buildProblem(List<Employee> employees, List<Position> positions,
                                         Settings settings, Map<String, List<String>> overrides) {
        // Global working-time rules apply to everyone as defaults; hand them to each
        // employee so the constraints fall back to them where there's no personal rule.
        List<dev.shiftsmith.domain.Rule> global = settings.getGlobalRules();
        for (Employee e : employees) e.setGlobalRules(global);
        List<ShiftAssignment> assignments = ScheduleExpander.expand(
                positions, employees, settings, overrides, LocalDate.now());
        // Deep-ish copy of employees is unnecessary: the solver only reads them.
        return new Schedule(new ArrayList<>(employees), assignments);
    }

    private ProblemDocument snapshot() {
        ProblemDocument d = new ProblemDocument();
        d.employees = new ArrayList<>(employees);
        d.positions = new ArrayList<>(positions);
        d.settings = settings;
        d.overrides = new HashMap<>(overrides);
        return d;
    }

    /**
     * Persist the given document, surfacing failures instead of swallowing them so
     * callers can react (e.g. answer a {@code 503}). A silent persist failure would
     * let the client believe an edit was durable while the in-memory state diverged
     * from the database, losing the edit on the next restart.
     */
    private void persist(ProblemDocument doc) {
        try {
            store.save(doc);
        } catch (Exception e) {
            LOG.error("Failed to persist problem", e);
            throw new PersistFailedException("Failed to persist the problem to the database", e);
        }
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
            broadcaster.fire();
            return;
        }
        solverManager.solveBuilder()
                .withProblemId(JOB_ID)
                .withProblem(problem)
                .withBestSolutionEventConsumer(event -> { this.bestSolution = event.solution(); broadcaster.fire(); })
                .withFinalBestSolutionEventConsumer(event -> { this.bestSolution = event.solution(); broadcaster.fire(); })
                .withExceptionHandler((id, ex) -> LOG.errorf(ex, "Solver job %s failed", id))
                .run();
        // Tell clients solving (re)started right away, before the first improvement.
        broadcaster.fire();
    }

    public void stopSolving() {
        solverManager.terminateEarly(JOB_ID);
        broadcaster.fire();
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
     * Replace the whole problem from the frontend, persist it and re-solve. Null
     * fields are left unchanged so partial syncs (e.g. settings only) are cheap.
     */
    public synchronized void replaceProblem(List<Employee> newEmployees, List<Position> newPositions,
                                            Settings newSettings, Map<String, List<String>> newOverrides) {
        // Resolve the candidate state (a null field leaves the current value untouched)
        // and trial-build it *before* committing. Expansion is what catches anything the
        // REST validator didn't, so by building first we never persist a document that
        // would throw — which would otherwise re-throw on the next boot and brick startup.
        List<Employee> nextEmployees = newEmployees != null ? newEmployees : employees;
        List<Position> nextPositions = newPositions != null ? newPositions : positions;
        Settings nextSettings = newSettings != null ? newSettings : settings;
        Map<String, List<String>> nextOverrides = newOverrides != null ? newOverrides : overrides;
        buildProblem(nextEmployees, nextPositions, nextSettings, nextOverrides);

        // Persist the resolved candidate *before* committing it to memory. A failed
        // write must not leave the in-memory state diverged from the database (the
        // edit would be silently lost on the next restart while the client believed
        // it durable). On failure persist() throws, the REST layer answers 503, and
        // our state is left untouched so the client can safely retry.
        ProblemDocument next = new ProblemDocument();
        next.employees = new ArrayList<>(nextEmployees);
        next.positions = new ArrayList<>(nextPositions);
        next.settings = nextSettings;
        next.overrides = new HashMap<>(nextOverrides);
        persist(next);

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

    /**
     * Build the full state payload the frontend consumes — over both
     * {@code GET /api/schedule} and the SSE stream. Synchronized so the snapshot
     * is internally consistent; copies the editable collections so they can be
     * serialized off-thread without a concurrent edit triggering a CME.
     */
    public synchronized ScheduleDTO snapshotDTO() {
        ScheduleDTO dto = new ScheduleDTO();
        dto.employees = new ArrayList<>(employees);
        dto.positions = new ArrayList<>(positions);
        dto.settings = settings;
        dto.overrides = new HashMap<>(overrides);

        List<ShiftAssignment> assignments = currentAssignments();
        dto.assignments = assignments.stream().map(ScheduleDTO.Slot::of).toList();
        dto.total = assignments.size();
        dto.staffed = (int) assignments.stream().filter(a -> a.getEmployee() != null).count();
        dto.unassigned = dto.total - dto.staffed;

        LocalDate today = LocalDate.now();
        dto.horizonStart = settings.horizonStart(today);
        dto.horizonEnd = settings.horizonEnd(today);

        SolverStatus status = status();
        dto.solverStatus = status == null ? "NOT_SOLVING" : status.name();

        Schedule best = bestSolution;
        if (best != null && best.getScore() != null) {
            HardMediumSoftScore s = best.getScore();
            dto.score = new ScheduleDTO.Score(s.hardScore(), s.mediumScore(), s.softScore());
        }
        return dto;
    }
}
