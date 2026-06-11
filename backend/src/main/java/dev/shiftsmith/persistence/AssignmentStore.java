package dev.shiftsmith.persistence;

import dev.shiftsmith.domain.ShiftAssignment;
import dev.shiftsmith.persistence.entity.AssignmentEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistence gateway for the {@code assignment} table — the solver's output made
 * durable (issue #47, Phase 2). The solved employee of each concrete window slot is
 * upserted here so the schedule survives a restart instead of being re-derived from
 * scratch and shown empty until the solver runs again.
 *
 * <p>Manual pins are persisted separately as {@code source = 'manual'} rows by the
 * document path ({@link ProblemStore}); this store only ever writes and replaces the
 * solver-produced ({@code source = 'solver'}) rows of the <em>current window</em>, so
 * pins, future rows and (later) past history are never disturbed.
 */
@ApplicationScoped
public class AssignmentStore {

    static final String SOLVER = "solver";

    /** Slot id as produced by {@code ScheduleExpander}: {@code <templateId>@<date>#<index>}. */
    public static String slotId(String templateId, LocalDate occurrenceDate, int slotIndex) {
        return templateId + "@" + occurrenceDate + "#" + slotIndex;
    }

    /**
     * Replace the solver-produced rows for {@code [windowStart, windowEnd)} with the
     * staffed, non-pinned slots of the latest best solution. Pinned slots are left to
     * the document path's {@code manual} rows; unstaffed slots are not persisted.
     */
    @Transactional
    public void persistSolvedWindow(List<ShiftAssignment> slots, LocalDate windowStart, LocalDate windowEnd) {
        AssignmentEntity.delete("source = ?1 and occurrenceDate >= ?2 and occurrenceDate < ?3",
                SOLVER, windowStart, windowEnd);
        Instant now = Instant.now();
        for (ShiftAssignment a : slots) {
            if (a.isPinned() || a.getEmployee() == null) continue;
            AssignmentEntity ae = new AssignmentEntity();
            ae.templateId = a.getShiftTemplateId();
            ae.occurrenceDate = a.getDate();
            ae.slotIndex = a.getSlotIndex();
            ae.startTs = a.getStart();
            ae.endTs = a.getEnd();
            ae.employeeId = a.getEmployee().getId();
            ae.pinned = false;
            ae.source = SOLVER;
            ae.solvedAt = now;
            ae.persist();
        }
    }

    /**
     * Worked shifts in {@code [from, to)} (staffed rows only), fed to the solver as
     * fixed history facts so the boundary constraints see real past hours/days. Both
     * solver-produced and manually-pinned past rows count as worked history.
     */
    @Transactional
    public List<AssignmentEntity> loadHistoryRows(LocalDate from, LocalDate to) {
        return AssignmentEntity.list(
                "occurrenceDate >= ?1 and occurrenceDate < ?2 and employeeId is not null", from, to);
    }

    /**
     * Every persisted assignment row (staffed or not) whose occurrence falls in
     * {@code [from, to)}, ordered for stable reads. Powers the windowed schedule read
     * (issue #47, Phase 3): unlike the live snapshot it spans past history and any
     * persisted future, not just the current solve window.
     */
    @Transactional
    public List<AssignmentEntity> loadRange(LocalDate from, LocalDate to) {
        return AssignmentEntity.list(
                "occurrenceDate >= ?1 and occurrenceDate < ?2", io.quarkus.panache.common.Sort.by("occurrenceDate").and("templateId").and("slotIndex"),
                from, to);
    }

    /**
     * The staffed employee of every persisted slot in {@code [windowStart, windowEnd)},
     * keyed by {@link #slotId}. Used to overlay a freshly expanded schedule on boot so
     * the last solved roster is shown immediately, before the solver re-runs.
     */
    @Transactional
    public Map<String, String> loadAssignedEmployees(LocalDate windowStart, LocalDate windowEnd) {
        Map<String, String> out = new HashMap<>();
        List<AssignmentEntity> rows = AssignmentEntity.list(
                "occurrenceDate >= ?1 and occurrenceDate < ?2", windowStart, windowEnd);
        for (AssignmentEntity ae : rows) {
            if (ae.employeeId == null) continue;
            out.put(slotId(ae.templateId, ae.occurrenceDate, ae.slotIndex), ae.employeeId);
        }
        return out;
    }
}
