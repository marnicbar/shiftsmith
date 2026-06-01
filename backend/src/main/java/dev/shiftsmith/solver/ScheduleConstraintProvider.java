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

    // ---------------------------------------------------------------- hard

    Constraint requiredSkills(ConstraintFactory f) {
        return f.forEach(ShiftAssignment.class)
                .filter(a -> a.getEmployee() != null
                        && a.getRequiredSkills() != null
                        && !a.getEmployee().getSkills().containsAll(a.getRequiredSkills()))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("Missing required skill");
    }

    Constraint vacation(ConstraintFactory f) {
        return f.forEach(ShiftAssignment.class)
                .filter(a -> a.getEmployee() != null && a.getEmployee().isOnVacation(a.getDate()))
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
                .filter(a -> a.getEmployee() != null
                        && !a.getEmployee().isAvailableFor(a.getDate(), a.getStartMinutes(), a.getEndMinutes()))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("Outside availability");
    }

    Constraint overlappingShifts(ConstraintFactory f) {
        return f.forEachUniquePair(ShiftAssignment.class,
                        Joiners.equal(ShiftAssignment::getEmployee),
                        Joiners.overlapping(ShiftAssignment::getStart, ShiftAssignment::getEnd))
                .filter((a, b) -> a.getEmployee() != null)
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("Overlapping shifts");
    }

    /** Minimum rest between two shifts, per the employee's restHours "at least" rule. */
    Constraint minRestBetweenShifts(ConstraintFactory f) {
        return f.forEachUniquePair(ShiftAssignment.class, Joiners.equal(ShiftAssignment::getEmployee))
                .filter((a, b) -> {
                    Employee e = a.getEmployee();
                    if (e == null) return false;
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

    /** Penalise (in minutes) any overage above the metric's "at most" limit for the period. */
    private Constraint overMax(ConstraintFactory f, String metric,
                               java.util.function.Function<ShiftAssignment, LocalDate> bucket, String name) {
        return f.forEach(ShiftAssignment.class)
                .filter(a -> a.getEmployee() != null)
                .groupBy(ShiftAssignment::getEmployee, bucket, ConstraintCollectors.sum(this::minutesOf))
                .filter((e, period, minutes) -> {
                    Integer max = e.maxLimit(metric, period);
                    return max != null && minutes > max * 60;
                })
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (e, period, minutes) -> minutes - e.maxLimit(metric, period) * 60)
                .asConstraint(name);
    }

    /**
     * Penalise a shortfall below the metric's "at least" limit, but only for
     * periods the employee actually works — an "at least" floor should not force
     * work onto otherwise empty days/weeks (which could be infeasible).
     */
    private Constraint underMin(ConstraintFactory f, String metric,
                                java.util.function.Function<ShiftAssignment, LocalDate> bucket, String name) {
        return f.forEach(ShiftAssignment.class)
                .filter(a -> a.getEmployee() != null)
                .groupBy(ShiftAssignment::getEmployee, bucket, ConstraintCollectors.sum(this::minutesOf))
                .filter((e, period, minutes) -> {
                    Integer min = e.minLimit(metric, period);
                    return min != null && minutes > 0 && minutes < min * 60;
                })
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (e, period, minutes) -> e.minLimit(metric, period) * 60 - minutes)
                .asConstraint(name);
    }

    /** No more than N consecutive worked days, per the consecDays "at most" rule. */
    Constraint maxConsecutiveDays(ConstraintFactory f) {
        return f.forEach(ShiftAssignment.class)
                .filter(a -> a.getEmployee() != null)
                .groupBy(ShiftAssignment::getEmployee, ConstraintCollectors.toSet(ShiftAssignment::getDate))
                .filter((e, days) -> {
                    Integer max = e.maxLimit("consecDays", Collections.min(days));
                    return max != null && longestRun(days) > max;
                })
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (e, days) -> longestRun(days) - e.maxLimit("consecDays", Collections.min(days)))
                .asConstraint("Too many consecutive days");
    }

    private int longestRun(Set<LocalDate> daySet) {
        List<LocalDate> days = new ArrayList<>(daySet);
        Collections.sort(days);
        int best = 1, run = 1;
        for (int i = 1; i < days.size(); i++) {
            if (days.get(i - 1).plusDays(1).equals(days.get(i))) run++;
            else run = 1;
            best = Math.max(best, run);
        }
        return days.isEmpty() ? 0 : best;
    }

    // -------------------------------------------------------------- medium

    /** Reward every staffed slot so the solver maximises coverage. */
    Constraint coverage(ConstraintFactory f) {
        return f.forEach(ShiftAssignment.class)
                .filter(a -> a.getEmployee() != null)
                .reward(HardMediumSoftScore.ONE_MEDIUM)
                .asConstraint("Staff every shift");
    }

    // ---------------------------------------------------------------- soft

    Constraint preferredEmployee(ConstraintFactory f) {
        return f.forEach(ShiftAssignment.class)
                .filter(a -> a.getEmployee() != null && a.isPreferred(a.getEmployee()))
                .reward(HardMediumSoftScore.ofSoft(4))
                .asConstraint("Preferred employee for shift");
    }

    /** Reward the hours of a shift that fall inside a preferred window. */
    Constraint preferredTimeBlock(ConstraintFactory f) {
        return f.forEach(ShiftAssignment.class)
                .filter(a -> a.getEmployee() != null
                        && a.getEmployee().preferredMinutes(a.getDate(), a.getStartMinutes(), a.getEndMinutes()) > 0)
                .reward(HardMediumSoftScore.ONE_SOFT,
                        a -> Math.round(a.getEmployee().preferredMinutes(a.getDate(), a.getStartMinutes(), a.getEndMinutes()) / 60f))
                .asConstraint("Preferred time block");
    }

    /** Penalise the hours of a shift that fall inside an undesired window. */
    Constraint undesiredTimeBlock(ConstraintFactory f) {
        return f.forEach(ShiftAssignment.class)
                .filter(a -> a.getEmployee() != null
                        && a.getEmployee().undesiredMinutes(a.getDate(), a.getStartMinutes(), a.getEndMinutes()) > 0)
                .penalize(HardMediumSoftScore.ONE_SOFT,
                        a -> Math.round(a.getEmployee().undesiredMinutes(a.getDate(), a.getStartMinutes(), a.getEndMinutes()) / 60f))
                .asConstraint("Undesired time block");
    }

    /** Nudge weekly hours towards a preferred target, penalising deviation. */
    Constraint preferredHoursPerWeek(ConstraintFactory f) {
        return f.forEach(ShiftAssignment.class)
                .filter(a -> a.getEmployee() != null)
                .groupBy(ShiftAssignment::getEmployee, ShiftAssignment::getWeekStart, ConstraintCollectors.sum(this::minutesOf))
                .filter((e, week, minutes) -> e.preferred("weekHours", week) != null)
                .penalize(HardMediumSoftScore.ONE_SOFT,
                        (e, week, minutes) -> Math.abs(minutes - e.preferred("weekHours", week) * 60) / 60)
                .asConstraint("Preferred weekly hours");
    }

    /** Spread shifts evenly: penalising count squared favours balanced workloads. */
    Constraint balanceWorkload(ConstraintFactory f) {
        return f.forEach(ShiftAssignment.class)
                .filter(a -> a.getEmployee() != null)
                .groupBy(ShiftAssignment::getEmployee, ConstraintCollectors.count())
                .penalize(HardMediumSoftScore.ONE_SOFT, (e, count) -> count * count)
                .asConstraint("Balance workload");
    }
}
