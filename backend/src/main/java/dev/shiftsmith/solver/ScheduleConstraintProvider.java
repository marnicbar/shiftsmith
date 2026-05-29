package dev.shiftsmith.solver;

import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;
import dev.shiftsmith.domain.Shift;

import java.time.Duration;

public class ScheduleConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[]{
                missingRequiredSkill(factory),
                overlappingShifts(factory),
                atLeast10HoursBetweenShifts(factory),
                maxOneShiftPerDay(factory),
                unavailableEmployee(factory),
                undesiredDay(factory),
                desiredDay(factory),
                balanceShiftAssignments(factory)
        };
    }

    // --- Hard constraints ---

    Constraint missingRequiredSkill(ConstraintFactory factory) {
        return factory.forEach(Shift.class)
                .filter(shift -> shift.getEmployee() != null
                        && !shift.getEmployee().getSkills().contains(shift.getRequiredSkill()))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Missing required skill");
    }

    Constraint overlappingShifts(ConstraintFactory factory) {
        return factory.forEachUniquePair(Shift.class,
                        Joiners.equal(Shift::getEmployee),
                        Joiners.overlapping(Shift::getStart, Shift::getEnd))
                .filter((s1, s2) -> s1.getEmployee() != null)
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Overlapping shifts");
    }

    Constraint atLeast10HoursBetweenShifts(ConstraintFactory factory) {
        return factory.forEachUniquePair(Shift.class,
                        Joiners.equal(Shift::getEmployee))
                .filter((s1, s2) -> {
                    if (s1.getEmployee() == null) return false;
                    Duration gap1 = Duration.between(s1.getEnd(), s2.getStart());
                    Duration gap2 = Duration.between(s2.getEnd(), s1.getStart());
                    return (gap1.isPositive() && gap1.toHours() < 10)
                            || (gap2.isPositive() && gap2.toHours() < 10);
                })
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("At least 10 hours between shifts");
    }

    Constraint maxOneShiftPerDay(ConstraintFactory factory) {
        return factory.forEachUniquePair(Shift.class,
                        Joiners.equal(Shift::getEmployee),
                        Joiners.equal(s -> s.getStart().toLocalDate()))
                .filter((s1, s2) -> s1.getEmployee() != null)
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Max one shift per day");
    }

    Constraint unavailableEmployee(ConstraintFactory factory) {
        return factory.forEach(Shift.class)
                .filter(shift -> shift.getEmployee() != null
                        && shift.getEmployee().getUnavailableDates().stream()
                                .anyMatch(shift::isOverlappingWithDate))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Unavailable employee");
    }

    // --- Soft constraints ---

    Constraint undesiredDay(ConstraintFactory factory) {
        return factory.forEach(Shift.class)
                .filter(shift -> shift.getEmployee() != null
                        && shift.getEmployee().getUndesiredDates().stream()
                                .anyMatch(shift::isOverlappingWithDate))
                .penalize(HardSoftScore.ONE_SOFT)
                .asConstraint("Undesired day for employee");
    }

    Constraint desiredDay(ConstraintFactory factory) {
        return factory.forEach(Shift.class)
                .filter(shift -> shift.getEmployee() != null
                        && shift.getEmployee().getDesiredDates().stream()
                                .anyMatch(shift::isOverlappingWithDate))
                .reward(HardSoftScore.ONE_SOFT)
                .asConstraint("Desired day for employee");
    }

    Constraint balanceShiftAssignments(ConstraintFactory factory) {
        // Penalize count^2 per employee so distributing shifts evenly scores better.
        return factory.forEach(Shift.class)
                .filter(shift -> shift.getEmployee() != null)
                .groupBy(Shift::getEmployee, ai.timefold.solver.core.api.score.stream.ConstraintCollectors.count())
                .penalize(HardSoftScore.ONE_SOFT, (employee, count) -> count * count)
                .asConstraint("Balance employee shift assignments");
    }
}
