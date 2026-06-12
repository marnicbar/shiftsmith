package dev.shiftsmith.service;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.SolverManager;
import ai.timefold.solver.core.api.solver.SolverStatus;
import dev.shiftsmith.domain.Block;
import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.Position;
import dev.shiftsmith.domain.Rule;
import dev.shiftsmith.domain.Schedule;
import dev.shiftsmith.domain.Settings;
import dev.shiftsmith.domain.ShiftAssignment;
import dev.shiftsmith.domain.ShiftTemplate;
import dev.shiftsmith.persistence.AssignmentStore;
import dev.shiftsmith.persistence.EmployeeStore;
import dev.shiftsmith.persistence.PersistFailedException;
import dev.shiftsmith.persistence.ProblemDocument;
import dev.shiftsmith.persistence.ProblemStore;
import dev.shiftsmith.realtime.ScheduleBroadcaster;
import dev.shiftsmith.rest.dto.Page;
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
    AssignmentStore assignmentStore;

    @Inject
    EmployeeStore employeeStore;

    @Inject
    dev.shiftsmith.persistence.PositionStore positionStore;

    @Inject
    dev.shiftsmith.persistence.SettingsStore settingsStore;

    @Inject
    ScheduleBroadcaster broadcaster;

    private final List<Employee> employees = new ArrayList<>();
    private final List<Position> positions = new ArrayList<>();
    private Settings settings = new Settings("week", 1);
    private Map<String, List<String>> overrides = new HashMap<>();

    private volatile Schedule bestSolution;

    /**
     * The last solved roster, persisted as {@code assignment} rows and reloaded on
     * boot, keyed by slot id. Overlays a freshly expanded schedule so the previous
     * solution is shown immediately after a restart, before the solver re-runs.
     */
    private volatile Map<String, String> persistedAssignments = Map.of();

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
        // Show the last solved roster immediately (overlaid on a fresh expansion) so a
        // restart doesn't blank the schedule until the solver runs again.
        reloadPersistedAssignments();
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
        Schedule problem = buildProblem(employees, positions, settings, overrides);
        appendHistoryFacts(problem);
        return problem;
    }

    /**
     * Load the worked shifts in the bounded lookback ({@code [lookbackStart, windowStart)})
     * and append them as fixed history facts so the boundary constraints (rest, consecutive
     * days, weekly/monthly hours) are correct at the leading edge of the window.
     */
    private void appendHistoryFacts(Schedule problem) {
        if (problem.getAssignments().isEmpty()) return;   // no window slots → nothing to bound
        LocalDate today = LocalDate.now();
        LocalDate windowStart = settings.horizonStart(today);
        LocalDate lookbackStart = dev.shiftsmith.solver.SolverScope.lookbackStart(employees, settings, today);
        if (!lookbackStart.isBefore(windowStart)) return; // no relevant boundary rules

        Map<String, Employee> byId = new HashMap<>();
        for (Employee e : employees) byId.put(e.getId(), e);

        List<dev.shiftsmith.persistence.entity.AssignmentEntity> rows;
        try {
            rows = assignmentStore.loadHistoryRows(lookbackStart, windowStart);
        } catch (Exception e) {
            LOG.error("Could not load history for the solver lookback", e);
            return;
        }
        for (var h : rows) {
            Employee e = byId.get(h.employeeId);
            if (e == null) continue;   // the person was removed; their past slot is now unstaffed
            ShiftAssignment a = new ShiftAssignment();
            a.setId(h.templateId + "@" + h.occurrenceDate + "#" + h.slotIndex);
            a.setShiftTemplateId(h.templateId);
            a.setSlotIndex(h.slotIndex);
            a.setDate(h.occurrenceDate);
            a.setStart(h.startTs);
            a.setEnd(h.endTs);
            a.setEmployee(e);
            a.setPinned(true);
            a.setHistory(true);
            a.setRequiredSkills(java.util.Set.of());
            a.setPreferredEmployeeIds(java.util.List.of());
            problem.getAssignments().add(a);
        }
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
        // Nothing to solve (no shifts) — keep the empty snapshot as the result and
        // clear any solver rows left over for an emptied window.
        if (problem.getAssignments().isEmpty()) {
            bestSolution = problem;
            persistSolved(problem);
            broadcaster.fire();
            return;
        }
        solverManager.solveBuilder()
                .withProblemId(JOB_ID)
                .withProblem(problem)
                .withBestSolutionEventConsumer(event -> { this.bestSolution = event.solution(); broadcaster.fire(); })
                .withFinalBestSolutionEventConsumer(event -> {
                    this.bestSolution = event.solution();
                    persistSolved(event.solution());
                    broadcaster.fire();
                })
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

    // --- granular, windowed reads (issue #47, Phase 3) -------------------
    // Synchronized snapshots of the in-memory problem (the canonical source) so the
    // client can load only what a view needs; the schedule range additionally reads
    // the durable assignment rows, spanning history beyond the live solve window.

    public synchronized List<String> skills() {
        return new ArrayList<>(settings.getSkills());
    }

    public synchronized Page<Employee> employeesPage(int page, int size) {
        return Page.of(employees, page, size);
    }

    public synchronized Page<Position> positionsPage(int page, int size) {
        return Page.of(positions, page, size);
    }

    public synchronized Optional<Employee> employee(String id) {
        return employees.stream().filter(e -> e.getId().equals(id)).findFirst();
    }

    public synchronized Optional<Position> position(String id) {
        return positions.stream().filter(p -> p.getId().equals(id)).findFirst();
    }

    /** An employee's availability blocks that project into {@code [from, to]} (inclusive). */
    public synchronized Optional<List<Block>> employeeAvailability(String id, LocalDate from, LocalDate to) {
        return employee(id).map(e -> {
            List<Block> out = new ArrayList<>();
            for (Block b : e.getBlocks()) {
                if (blockProjectsInto(b, from, to)) out.add(b);
            }
            return out;
        });
    }

    public synchronized Optional<List<Rule>> employeeRules(String id) {
        return employee(id).map(e -> new ArrayList<>(e.getRules()));
    }

    public synchronized Optional<List<ShiftTemplate>> positionTemplates(String id) {
        return position(id).map(p -> new ArrayList<>(p.getShifts()));
    }

    private static boolean blockProjectsInto(Block b, LocalDate from, LocalDate to) {
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (b.occursOn(d)) return true;
        }
        return false;
    }

    /**
     * The persisted assignment slots whose occurrence falls in {@code [from, to)},
     * optionally narrowed to one person ({@code person:<id>}) or position
     * ({@code position:<id>}). Reads the durable {@code assignment} rows, so it spans
     * history and any persisted future — not just the live solve window.
     */
    public List<ScheduleDTO.Slot> rangeSlots(LocalDate from, LocalDate to, String scope) {
        Map<String, String> templatePosition;
        synchronized (this) {
            templatePosition = new HashMap<>();
            for (Position p : positions) {
                for (ShiftTemplate t : p.getShifts()) templatePosition.put(t.getId(), p.getId());
            }
        }
        String personId = scopeValue(scope, "person");
        String positionId = scopeValue(scope, "position");

        List<ScheduleDTO.Slot> out = new ArrayList<>();
        for (var a : assignmentStore.loadRange(from, to)) {
            if (personId != null && !personId.equals(a.employeeId)) continue;
            String pos = templatePosition.get(a.templateId);
            if (positionId != null && !positionId.equals(pos)) continue;
            out.add(new ScheduleDTO.Slot(
                    AssignmentStore.slotId(a.templateId, a.occurrenceDate, a.slotIndex),
                    pos, a.templateId, a.slotIndex, a.occurrenceDate, a.startTs, a.endTs, a.employeeId, a.pinned));
        }
        return out;
    }

    /** Extract {@code <id>} from a {@code "<kind>:<id>"} scope token, or null. */
    private static String scopeValue(String scope, String kind) {
        if (scope == null) return null;
        String prefix = kind + ":";
        return scope.startsWith(prefix) ? scope.substring(prefix.length()) : null;
    }

    // --- granular, concurrency-safe writes (issue #47, Phase 4) ----------
    // Each mutates exactly one employee in the durable rows (with an optimistic version
    // check), mirrors the change into the in-memory problem and re-solves. Synchronized
    // so the DB write, the in-memory update and the re-solve are one atomic step and
    // concurrent writers are serialized — edits to different people never conflict, a
    // same-person stale write is a 409.

    public Optional<Long> employeeVersion(String id) {
        return employeeStore.versionOf(id);
    }

    public synchronized EmployeeStore.Outcome createEmployee(Employee emp) {
        EmployeeStore.Outcome outcome = employeeStore.create(emp);
        if (outcome.result() == EmployeeStore.Result.OK) {
            employees.add(emp);
            startSolving();
        }
        return outcome;
    }

    public synchronized EmployeeStore.Outcome updateEmployee(Employee emp, long expectedVersion) {
        EmployeeStore.Outcome outcome = employeeStore.update(emp, expectedVersion);
        if (outcome.result() == EmployeeStore.Result.OK) {
            employees.removeIf(e -> e.getId().equals(emp.getId()));
            employees.add(emp);
            startSolving();
        }
        return outcome;
    }

    public synchronized EmployeeStore.Outcome deleteEmployee(String id, long expectedVersion) {
        EmployeeStore.Outcome outcome = employeeStore.delete(id, expectedVersion);
        if (outcome.result() == EmployeeStore.Result.OK) {
            employees.removeIf(e -> e.getId().equals(id));
            startSolving();
        }
        return outcome;
    }

    public Optional<Long> positionVersion(String id) {
        return positionStore.versionOf(id);
    }

    public synchronized dev.shiftsmith.persistence.PositionStore.Outcome createPosition(Position position) {
        var outcome = positionStore.create(position);
        if (outcome.result() == dev.shiftsmith.persistence.PositionStore.Result.OK) {
            positions.add(position);
            startSolving();
        }
        return outcome;
    }

    public synchronized dev.shiftsmith.persistence.PositionStore.Outcome updatePosition(Position position, long expectedVersion) {
        var outcome = positionStore.update(position, expectedVersion);
        if (outcome.result() == dev.shiftsmith.persistence.PositionStore.Result.OK) {
            positions.removeIf(p -> p.getId().equals(position.getId()));
            positions.add(position);
            startSolving();
        }
        return outcome;
    }

    public synchronized dev.shiftsmith.persistence.PositionStore.Outcome deletePosition(String id, long expectedVersion) {
        var outcome = positionStore.delete(id, expectedVersion);
        if (outcome.result() == dev.shiftsmith.persistence.PositionStore.Result.OK) {
            positions.removeIf(p -> p.getId().equals(id));
            startSolving();
        }
        return outcome;
    }

    /**
     * Pin one shift occurrence to the given employees (manual override), persist it and
     * re-solve. Returns false if the template doesn't exist (a 404). The overrides map
     * stays the in-memory source of pins; the manual {@code assignment} rows are written
     * so the pin survives a restart.
     */
    public synchronized boolean pinOccurrence(String templateId, LocalDate date, List<String> employeeIds) {
        ShiftTemplate template = findTemplate(templateId);
        if (template == null) return false;
        java.util.Set<String> known = new java.util.HashSet<>();
        for (Employee e : employees) known.add(e.getId());
        List<String> ids = new ArrayList<>();
        for (String id : employeeIds) ids.add(known.contains(id) ? id : null);

        assignmentStore.pinOccurrence(templateId, date, ids,
                dev.shiftsmith.persistence.ProblemMapper.occurrenceStart(template, date),
                dev.shiftsmith.persistence.ProblemMapper.occurrenceEnd(template, date),
                template.getHeadcount());
        overrides.put(templateId + "@" + date, new ArrayList<>(employeeIds));
        startSolving();
        return true;
    }

    /** Remove a manual pin from one occurrence (idempotent) and re-solve. */
    public synchronized void unpinOccurrence(String templateId, LocalDate date) {
        overrides.remove(templateId + "@" + date);
        assignmentStore.unpinOccurrence(templateId, date);
        startSolving();
    }

    private ShiftTemplate findTemplate(String templateId) {
        for (Position p : positions) {
            for (ShiftTemplate t : p.getShifts()) {
                if (t.getId().equals(templateId)) return t;
            }
        }
        return null;
    }

    public Optional<Long> settingsVersion() {
        return settingsStore.version();
    }

    public synchronized dev.shiftsmith.persistence.SettingsStore.Outcome updateSettings(Settings newSettings, long expectedVersion) {
        var outcome = settingsStore.update(newSettings, expectedVersion);
        if (outcome.result() == dev.shiftsmith.persistence.SettingsStore.Result.OK) {
            settings = newSettings;
            startSolving();
        }
        return outcome;
    }

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
        // The save cleared the prior solver rows; drop the now-stale overlay so the gap
        // before the re-solve completes doesn't surface an outdated roster.
        reloadPersistedAssignments();
        startSolving();
    }

    /**
     * Best solved assignments, overlaid on a fresh expansion of the current problem.
     * The in-memory best solution wins; where it has nothing to say about a slot (e.g.
     * just after a restart, before the solver has run), the last persisted solution is
     * used so the schedule isn't shown blank.
     */
    public synchronized List<ShiftAssignment> currentAssignments() {
        List<ShiftAssignment> fresh = ScheduleExpander.expand(
                positions, employees, settings, overrides, LocalDate.now());
        Map<String, Employee> byId = new HashMap<>();
        for (Employee e : employees) byId.put(e.getId(), e);

        Schedule best = bestSolution;
        Map<String, Employee> solved = new HashMap<>();
        if (best != null && best.getAssignments() != null) {
            for (ShiftAssignment a : best.getAssignments()) solved.put(a.getId(), a.getEmployee());
        }
        Map<String, String> persisted = persistedAssignments;
        for (ShiftAssignment a : fresh) {
            if (a.isPinned()) continue;
            if (solved.containsKey(a.getId())) {
                a.setEmployee(solved.get(a.getId()));        // live solver state is authoritative
            } else if (persisted.containsKey(a.getId())) {
                a.setEmployee(byId.get(persisted.get(a.getId())));  // fall back to the persisted roster
            }
        }
        return fresh;
    }

    /** Persist the solver's window slots so the roster survives a restart, then refresh the overlay. */
    private void persistSolved(Schedule solution) {
        if (solution == null || solution.getAssignments() == null) return;
        LocalDate today = LocalDate.now();
        LocalDate start = settings.horizonStart(today);
        LocalDate end = settings.horizonEnd(today);
        try {
            assignmentStore.persistSolvedWindow(solution.getAssignments(), start, end);
            persistedAssignments = assignmentStore.loadAssignedEmployees(start, end);
        } catch (Exception e) {
            // A persistence hiccup must not crash the solver thread; the in-memory best
            // solution still serves the UI and the next solve will retry the write.
            LOG.error("Could not persist the solved schedule", e);
        }
    }

    /** Reload the persisted roster overlay for the current window. */
    private void reloadPersistedAssignments() {
        LocalDate today = LocalDate.now();
        try {
            persistedAssignments = assignmentStore.loadAssignedEmployees(
                    settings.horizonStart(today), settings.horizonEnd(today));
        } catch (Exception e) {
            LOG.error("Could not load persisted assignments", e);
        }
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
