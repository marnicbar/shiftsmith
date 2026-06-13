package dev.shiftsmith.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Server-side sanity check on a written resource — the last guard before an
 * employee/position/settings edit is persisted and handed to the solver.
 *
 * <p>The frontend constrains its inputs, but a stale, replayed, or hand-crafted
 * request can carry values the UI would never produce: an out-of-range shift time
 * that makes {@link ScheduleExpander} throw {@code DateTimeException}, an absurd
 * {@code headcount} that allocates billions of slots (OOM), or a giant
 * {@code horizonCount} that turns the day-by-day expansion into a multi-million
 * iteration loop (DoS). Rejecting these before the write keeps a poison value out
 * of the database, where it would otherwise re-fire the same exception inside the
 * boot observer.
 *
 * <p>This mirrors {@link DuplicateId} and {@link CalendarOverlap} as a 400-able
 * check the UI can't bypass: a single human-readable message on the first problem
 * found, or empty when the input is safe to persist.
 */
public final class ProblemValidation {

    private ProblemValidation() {}

    /** A single shift slot needing more people than this is certainly a bad payload. */
    public static final int MAX_HEADCOUNT = 1000;

    /** Minutes-from-midnight: a start must land on a real wall-clock time (00:00–23:59). */
    private static final int MAX_START = 1439;

    /** An end may reach 1440 ("until midnight"); the expander rolls that to the next day. */
    private static final int MAX_END = 1440;

    /**
     * The first validation problem found, described for a 400 response, or empty
     * when every employee, position, shift, and the settings are within bounds.
     */
    public static Optional<String> firstError(List<Employee> employees, List<Position> positions,
                                              Settings settings) {
        if (employees != null) {
            for (Employee e : employees) {
                if (isBlank(e.getId())) {
                    return Optional.of("An employee is missing an id.");
                }
            }
        }
        if (positions != null) {
            for (Position p : positions) {
                if (isBlank(p.getId())) {
                    return Optional.of("A position is missing an id.");
                }
                if (p.getShifts() == null) continue;
                for (ShiftTemplate t : p.getShifts()) {
                    Optional<String> err = checkShift(p, t);
                    if (err.isPresent()) return err;
                }
            }
        }
        if (settings != null) {
            int count = settings.getHorizonCount();
            if (count < 1) {
                return Optional.of("Horizon count must be at least 1 (got " + count + ").");
            }
            // Cap the *duration* of the solve window, not the raw count, so the limit
            // means the same thing whatever the unit (day/week/month).
            LocalDate today = LocalDate.now();
            long days = ChronoUnit.DAYS.between(settings.horizonStart(today), settings.rawHorizonEnd(today));
            if (days > Settings.MAX_HORIZON_DAYS) {
                return Optional.of("The solve window is too long (" + days + " days); the maximum is "
                        + Settings.MAX_HORIZON_DAYS + " days (about two years).");
            }
        }
        return Optional.empty();
    }

    private static Optional<String> checkShift(Position p, ShiftTemplate t) {
        String where = "Shift '" + label(t.getName(), t.getId()) + "' in position '"
                + label(p.getName(), p.getId()) + "'";
        if (isBlank(t.getId())) {
            return Optional.of("A shift in position '" + label(p.getName(), p.getId()) + "' is missing an id.");
        }
        if (t.getStart() < 0 || t.getStart() > MAX_START) {
            return Optional.of(where + " has an out-of-range start time (" + t.getStart() + ").");
        }
        if (t.getEnd() < 0 || t.getEnd() > MAX_END) {
            return Optional.of(where + " has an out-of-range end time (" + t.getEnd() + ").");
        }
        if (t.getHeadcount() < 1 || t.getHeadcount() > MAX_HEADCOUNT) {
            return Optional.of(where + " has an out-of-range headcount (" + t.getHeadcount() + ").");
        }
        return Optional.empty();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String label(String name, String id) {
        return name != null && !name.isBlank() ? name : (id != null ? id : "?");
    }
}
