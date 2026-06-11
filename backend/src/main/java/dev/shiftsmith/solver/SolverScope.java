package dev.shiftsmith.solver;

import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.Settings;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * Bounds the solver's view of history (issue #47, Phase 2). The working set is
 * {@code [lookbackStart, windowEnd)}: the window plus exactly the lead-in each
 * boundary constraint needs, never the whole dataset. A shift older than the
 * lookback can't affect any window decision and is never loaded.
 *
 * <pre>
 * lookbackStart = min over the rules that actually exist of:
 *   firstDayOfISOWeek(windowStart)     // weekHours bucket completeness
 *   firstDayOfMonth(windowStart)       // monthHours bucket completeness
 *   windowStart - maxConsecDays        // a consecutive-day run leading into the window
 *   windowStart - ceil(maxRestHours/24)// a shift ending just before the window (rest)
 * </pre>
 *
 * The bound is at most about two months — never the full history.
 */
public final class SolverScope {

    private SolverScope() {}

    /** Inclusive first day of the data the solver must load, given the rules in force at the window start. */
    public static LocalDate lookbackStart(List<Employee> employees, Settings settings, LocalDate today) {
        LocalDate windowStart = settings.horizonStart(today);
        LocalDate earliest = windowStart;

        int maxConsec = 0;
        int maxRest = 0;
        boolean anyWeek = false;
        boolean anyMonth = false;
        for (Employee e : employees) {
            Integer consec = e.maxLimit("consecDays", windowStart);
            if (consec != null) maxConsec = Math.max(maxConsec, consec);
            Integer rest = e.minLimit("restHours", windowStart);
            if (rest != null) maxRest = Math.max(maxRest, rest);
            anyWeek = anyWeek || hasRule(e, "weekHours", windowStart);
            anyMonth = anyMonth || hasRule(e, "monthHours", windowStart);
        }

        if (anyWeek) earliest = earlier(earliest, windowStart.with(DayOfWeek.MONDAY));
        if (anyMonth) earliest = earlier(earliest, windowStart.withDayOfMonth(1));
        if (maxConsec > 0) earliest = earlier(earliest, windowStart.minusDays(maxConsec));
        if (maxRest > 0) earliest = earlier(earliest, windowStart.minusDays((long) Math.ceil(maxRest / 24.0)));
        return earliest;
    }

    private static boolean hasRule(Employee e, String metric, LocalDate date) {
        return e.maxLimit(metric, date) != null
                || e.minLimit(metric, date) != null
                || e.preferred(metric, date) != null;
    }

    private static LocalDate earlier(LocalDate a, LocalDate b) {
        return b.isBefore(a) ? b : a;
    }
}
