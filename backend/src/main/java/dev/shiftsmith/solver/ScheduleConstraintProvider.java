package dev.shiftsmith.solver;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;
import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.ShiftAssignment;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * All scheduling constraints, derived from the Personnel and Positions views.
 *
 * Hard   — required skills, vacation, overlaps, rest, daily/weekly/monthly hour
 *          limits and consecutive-day caps. The solver will never violate these.
 * Medium — coverage: every staffed slot is rewarded, so the solver fills as many
 *          slots as it can without breaking a hard rule.
 * Soft   — preferred employees, preferred/undesired time blocks, preferred-hours
 *          targets and fair workload distribution.
 *
 * <p><b>History (issue #47, Phase 2).</b> Worked shifts from before the solve window
 * are loaded as fixed facts ({@link ShiftAssignment#isHistory()}). They make the
 * boundary aggregates correct — rest, consecutive days and weekly/monthly hours all
 * count them — but they are <em>not</em> decisions: per-shift rules, coverage and
 * preferences ignore them, and an aggregate is only penalised where a real window
 * slot is involved (a purely historical breach can't be fixed, so it isn't blamed on
 * the window).
 */
public class ScheduleConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory f) {
        return new Constraint[]{
                // hard
                requiredSkills(f),
                vacation(f),
                availability(f),
                overlappingShifts(f),
                minRestBetweenShifts(f),
                maxHoursPerDay(f),
                maxHoursPerWeek(f),
                maxHoursPerMonth(f),
                minHoursPerDay(f),
                minHoursPerWeek(f),
                minHoursPerMonth(f),
                maxConsecutiveDays(f),
                // medium
                coverage(f),
                // soft
                preferredEmployee(f),
                preferredTimeBlock(f),
                undesiredTimeBlock(f),
                preferredHoursPerWeek(f),
                balanceWorkload(f),
        };
    }

    private int minutesOf(ShiftAssignment a) {
        return (int) Duration.between(a.getStart(), a.getEnd()).toMinutes();
    }

    /** Minutes that count towards coverage/balance: a history slot contributes none. */
    private int windowMinutes(ShiftAssignment a) {
        return a.isHistory() ? 0 : minutesOf(a);
    }

    // ---------------------------------------------------------------- hard
    // Per-shift hard rules only judge real (window) decisions; a history slot's
    // skills/vacation/availability already happened and can't be changed.

    Constraint requiredSkills(ConstraintFactory f) {
        return f.forEach(ShiftAssignment.class)
                .filter(a -> !a.isHistory()
                        && a.getEmployee() != null
                        && a.getRequiredSkills() != null
                        && !a.getEmployee().getSkills().containsAll(a.getRequiredSkills()))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("Missing required skill");
    }

    Constraint vacation(ConstraintFactory f) {
        return f.forEach(ShiftAssignment.class)
                .filter(a -> !a.isHistory() && a.getEmployee() != null && a.getEmployee().isOnVacation(a.getDate()))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("Employee on vacation");
    }

    /**
     * Employees are available only inside the windows defined by their preferred
     * and undesired blocks (an empty calendar means unavailable). A shift that
     * doesn't fit entirely within one window cannot be assigned to them.
     */
    Constraint availability(ConstraintFactory f) {
        return f.forEach(ShiftAssignment.class)
                .filter(a -> !a.isHistory()
                        && a.getEmployee() != null
                        && !a.getEmployee().isAvailableFor(a.getDate(), a.getStartMinutes(), a.getEndMinutes()))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("Outside availability");
    }

    Constraint overlappingShifts(ConstraintFactory f) {
        return f.forEachUniquePair(ShiftAssignment.class,
                        Joiners.equal(ShiftAssignment::getEmployee),
                        Joiners.overlapping(ShiftAssignment::getStart, ShiftAssignment::getEnd))
                .filter((a, b) -> a.getEmployee() != null && (!a.isHistory() || !b.isHistory()))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("Overlapping shifts");
    }

    /** Minimum rest between two shifts, per the employee's restHours "at least" rule. */
    Constraint minRestBetweenShifts(ConstraintFactory f) {
        return f.forEachUniquePair(ShiftAssignment.class, Joiners.equal(ShiftAssignment::getEmployee))
                .filter((a, b) -> {
                    Employee e = a.getEmployee();
                    if (e == null) return false;
                    if (a.isHistory() && b.isHistory()) return false;   // past-only pair: immutable
                    Integer rest = e.minLimit("restHours", a.getDate());
                    if (rest == null) return false;
                    long gap1 = Duration.between(a.getEnd(), b.getStart()).toMinutes();
                    long gap2 = Duration.between(b.getEnd(), a.getStart()).toMinutes();
                    long minGap = rest * 60L;
                    return (gap1 >= 0 && gap1 < minGap) || (gap2 >= 0 && gap2 < minGap);
                })
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("Too little rest between shifts");
    }

    Constraint maxHoursPerDay(ConstraintFactory f) {
        return overMax(f, "dayHours", ShiftAssignment::getDate, "More than the daily hour limit");
    }

    Constraint maxHoursPerWeek(ConstraintFactory f) {
        return overMax(f, "weekHours", ShiftAssignment::getWeekStart, "More than the weekly hour limit");
    }

    Constraint maxHoursPerMonth(ConstraintFactory f) {
        return overMax(f, "monthHours", ShiftAssignment::getMonthStart, "More than the monthly hour limit");
    }

    Constraint minHoursPerDay(ConstraintFactory f) {
        return underMin(f, "dayHours", ShiftAssignment::getDate, "Fewer than the daily hour minimum");
    }

    Constraint minHoursPerWeek(ConstraintFactory f) {
        return underMin(f, "weekHours", ShiftAssignment::getWeekStart, "Fewer than the weekly hour minimum");
    }

    Constraint minHoursPerMonth(ConstraintFactory f) {
        return underMin(f, "monthHours", ShiftAssignment::getMonthStart, "Fewer than the monthly hour minimum");
    }

    /**
     * Penalise (in minutes) any overage above the metric's "at most" limit for the
     * period. History hours count towards the total (so a partial boundary week/month
     * is complete), but a period is only penalised when it contains a window slot —
     * the solver can't undo a purely historical overage.
     */
    private Constraint overMax(ConstraintFactory f, String metric,
                               java.util.function.Function<ShiftAssignment, LocalDate> bucket, String name) {
        return f.forEach(ShiftAssignment.class)
                .filter(a -> a.getEmployee() != null)
                .groupBy(ShiftAssignment::getEmployee, bucket,
                        ConstraintCollectors.sum(this::minutesOf),
                        ConstraintCollectors.sum(this::windowMinutes))
                .filter((e, period, minutes, windowMinutes) -> {
                    Integer max = e.maxLimit(metric, period);
                    return windowMinutes > 0 && max != null && minutes > max * 60;
                })
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (e, period, minutes, windowMinutes) -> minutes - e.maxLimit(metric, period) * 60)
                .asConstraint(name);
    }

    /**
     * Penalise a shortfall below the metric's "at least" limit, but only for periods
     * the employee actually works (an "at least" floor should not force work onto
     * otherwise empty days/weeks) and that include a window slot to act on.
     */
    private Constraint underMin(ConstraintFactory f, String metric,
                                java.util.function.Function<ShiftAssignment, LocalDate> bucket, String name) {
        return f.forEach(ShiftAssignment.class)
                .filter(a -> a.getEmployee() != null)
                .groupBy(ShiftAssignment::getEmployee, bucket,
                        ConstraintCollectors.sum(this::minutesOf),
                        ConstraintCollectors.sum(this::windowMinutes))
                .filter((e, period, minutes, windowMinutes) -> {
                    Integer min = e.minLimit(metric, period);
                    return windowMinutes > 0 && min != null && minutes > 0 && minutes < min * 60;
                })
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (e, period, minutes, windowMinutes) -> e.minLimit(metric, period) * 60 - minutes)
                .asConstraint(name);
    }

    /** No more than N consecutive worked days, per the consecDays "at most" rule. */
    Constraint maxConsecutiveDays(ConstraintFactory f) {
        return f.forEach(ShiftAssignment.class)
                .filter(a -> a.getEmployee() != null)
                .groupBy(ShiftAssignment::getEmployee,
                        ConstraintCollectors.toSet(ShiftAssignment::getDate),
                        ConstraintCollectors.conditionally(a -> !a.isHistory(),
                                ConstraintCollectors.toSet(ShiftAssignment::getDate)))
                .filter((e, days, windowDays) -> {
                    Integer max = e.maxLimit("consecDays", Collections.min(days));
                    return max != null && longestRunWithWindow(days, windowDays) > max;
                })
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (e, days, windowDays) -> longestRunWithWindow(days, windowDays)
                                - e.maxLimit("consecDays", Collections.min(days)))
                .asConstraint("Too many consecutive days");
    }

    /**
     * Longest run of consecutive days that includes at least one window day. A run made
     * up entirely of history is immovable and so doesn't count — only runs the solver
     * can actually shorten are penalised.
     */
    private int longestRunWithWindow(Set<LocalDate> allDays, Set<LocalDate> windowDays) {
        List<LocalDate> days = new ArrayList<>(allDays);
        Collections.sort(days);
        int best = 0;
        int i = 0;
        while (i < days.size()) {
            int j = i;
            boolean hasWindow = windowDays.contains(days.get(i));
            while (j + 1 < days.size() && days.get(j).plusDays(1).equals(days.get(j + 1))) {
                j++;
                if (windowDays.contains(days.get(j))) hasWindow = true;
            }
            if (hasWindow) best = Math.max(best, j - i + 1);
            i = j + 1;
        }
        return best;
    }

    // -------------------------------------------------------------- medium

    /** Reward every staffed window slot so the solver maximises coverage. */
    Constraint coverage(ConstraintFactory f) {
        return f.forEach(ShiftAssignment.class)
                .filter(a -> !a.isHistory() && a.getEmployee() != null)
                .reward(HardMediumSoftScore.ONE_MEDIUM)
                .asConstraint("Staff every shift");
    }

    // ---------------------------------------------------------------- soft

    /** Soft reward for placing a shift's preferred employee on it (an explicit match weight). */
    static final int PREFERRED_EMPLOYEE_REWARD = 4;

    Constraint preferredEmployee(ConstraintFactory f) {
        return f.forEach(ShiftAssignment.class)
                .filter(a -> !a.isHistory() && a.getEmployee() != null && a.isPreferred(a.getEmployee()))
                .reward(HardMediumSoftScore.ONE_SOFT, a -> PREFERRED_EMPLOYEE_REWARD)
                .asConstraint("Preferred employee for shift");
    }

    /** Reward the hours of a shift that fall inside a preferred window. */
    Constraint preferredTimeBlock(ConstraintFactory f) {
        return f.forEach(ShiftAssignment.class)
                .filter(a -> !a.isHistory() && a.getEmployee() != null
                        && a.getEmployee().preferredMinutes(a.getDate(), a.getStartMinutes(), a.getEndMinutes()) > 0)
                .reward(HardMediumSoftScore.ONE_SOFT,
                        a -> Math.round(a.getEmployee().preferredMinutes(a.getDate(), a.getStartMinutes(), a.getEndMinutes()) / 60f))
                .asConstraint("Preferred time block");
    }

    /** Penalise the hours of a shift that fall inside an undesired window. */
    Constraint undesiredTimeBlock(ConstraintFactory f) {
        return f.forEach(ShiftAssignment.class)
                .filter(a -> !a.isHistory() && a.getEmployee() != null
                        && a.getEmployee().undesiredMinutes(a.getDate(), a.getStartMinutes(), a.getEndMinutes()) > 0)
                .penalize(HardMediumSoftScore.ONE_SOFT,
                        a -> Math.round(a.getEmployee().undesiredMinutes(a.getDate(), a.getStartMinutes(), a.getEndMinutes()) / 60f))
                .asConstraint("Undesired time block");
    }

    /** Nudge weekly hours towards a preferred target, penalising deviation. */
    Constraint preferredHoursPerWeek(ConstraintFactory f) {
        return f.forEach(ShiftAssignment.class)
                .filter(a -> a.getEmployee() != null)
                .groupBy(ShiftAssignment::getEmployee, ShiftAssignment::getWeekStart,
                        ConstraintCollectors.sum(this::minutesOf),
                        ConstraintCollectors.sum(this::windowMinutes))
                .filter((e, week, minutes, windowMinutes) -> windowMinutes > 0 && e.preferred("weekHours", week) != null)
                .penalize(HardMediumSoftScore.ONE_SOFT,
                        (e, week, minutes, windowMinutes) -> Math.abs(minutes - e.preferred("weekHours", week) * 60) / 60)
                .asConstraint("Preferred weekly hours");
    }

    /** Spread window shifts evenly: penalising count squared favours balanced workloads. */
    Constraint balanceWorkload(ConstraintFactory f) {
        return f.forEach(ShiftAssignment.class)
                .filter(a -> !a.isHistory() && a.getEmployee() != null)
                .groupBy(ShiftAssignment::getEmployee, ConstraintCollectors.count())
                .penalize(HardMediumSoftScore.ONE_SOFT, (e, count) -> count * count)
                .asConstraint("Balance workload");
    }
}
