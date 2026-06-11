package dev.shiftsmith.solver;

import ai.timefold.solver.core.api.score.stream.test.ConstraintVerifier;
import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.Schedule;
import dev.shiftsmith.domain.ShiftAssignment;
import dev.shiftsmith.support.SolverHarness;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.shiftsmith.support.Fixtures.assignment;
import static dev.shiftsmith.support.Fixtures.availableAllDay;
import static dev.shiftsmith.support.Fixtures.day;
import static dev.shiftsmith.support.Fixtures.employee;
import static dev.shiftsmith.support.Fixtures.rule;
import static dev.shiftsmith.support.Fixtures.vacation;
import static dev.shiftsmith.support.Fixtures.window;

/**
 * Unit tests for the individual scoring constraints, driven with Timefold's
 * {@link ConstraintVerifier}. Each test exercises exactly one constraint over a
 * handful of facts and asserts its impact (penalty weight, reward weight, or no
 * impact) — so a rule is verified in isolation from the rest of the score function.
 *
 * <p>{@code penalizesBy}/{@code rewardsWith} assert the summed match weight at the
 * constraint's own score level (hard, medium or soft).
 */
class ScheduleConstraintProviderTest {

    private final ConstraintVerifier<ScheduleConstraintProvider, Schedule> verifier =
            SolverHarness.constraintVerifier();

    // ------------------------------------------------------------- skills

    @Test
    void missingRequiredSkill_penalizesWhenEmployeeLacksSkill() {
        Employee e = availableAllDay(employee("e1", "Floor"), day(0));
        ShiftAssignment a = assignment("a1", day(0), 540, 1020, e, "Bar"); // needs Bar, has Floor
        verifier.verifyThat(ScheduleConstraintProvider::requiredSkills).given(e, a).penalizesBy(1);
    }

    @Test
    void missingRequiredSkill_silentWhenEmployeeHasSkill() {
        Employee e = availableAllDay(employee("e1", "Bar"), day(0));
        ShiftAssignment a = assignment("a1", day(0), 540, 1020, e, "Bar");
        verifier.verifyThat(ScheduleConstraintProvider::requiredSkills).given(e, a).penalizesBy(0);
    }

    // ------------------------------------------------------------- vacation

    @Test
    void vacation_penalizesAssignmentOnTimeOff() {
        Employee e = availableAllDay(employee("e1", "Bar"), day(0));
        e.getBlocks().add(vacation(day(0)));
        ShiftAssignment a = assignment("a1", day(0), 540, 1020, e, "Bar");
        verifier.verifyThat(ScheduleConstraintProvider::vacation).given(e, a).penalizesBy(1);
    }

    @Test
    void vacation_silentWhenNotOnVacation() {
        Employee e = availableAllDay(employee("e1", "Bar"), day(0));
        ShiftAssignment a = assignment("a1", day(0), 540, 1020, e, "Bar");
        verifier.verifyThat(ScheduleConstraintProvider::vacation).given(e, a).penalizesBy(0);
    }

    // --------------------------------------------------------- availability

    @Test
    void availability_penalizesShiftOutsideEveryWindow() {
        Employee e = employee("e1", "Bar");
        e.getBlocks().add(window("pref", day(0), 540, 720)); // only 09:00–12:00 available
        ShiftAssignment a = assignment("a1", day(0), 540, 1020, e, "Bar"); // 09:00–17:00 spills out
        verifier.verifyThat(ScheduleConstraintProvider::availability).given(e, a).penalizesBy(1);
    }

    @Test
    void availability_acceptsShiftThatFitsWindow() {
        Employee e = employee("e1", "Bar");
        e.getBlocks().add(window("pref", day(0), 480, 1080)); // 08:00–18:00
        ShiftAssignment a = assignment("a1", day(0), 540, 1020, e, "Bar"); // 09:00–17:00 fits
        verifier.verifyThat(ScheduleConstraintProvider::availability).given(e, a).penalizesBy(0);
    }

    @Test
    void availability_emptyCalendarMeansUnavailable() {
        Employee e = employee("e1", "Bar"); // no blocks at all
        ShiftAssignment a = assignment("a1", day(0), 540, 1020, e, "Bar");
        verifier.verifyThat(ScheduleConstraintProvider::availability).given(e, a).penalizesBy(1);
    }

    @Test
    void availability_mergesAdjacentWindows() {
        Employee e = employee("e1", "Bar");
        e.getBlocks().add(window("pref", day(0), 480, 720));   // 08:00–12:00
        e.getBlocks().add(window("undes", day(0), 720, 1080)); // 12:00–18:00, touches → merges
        ShiftAssignment a = assignment("a1", day(0), 600, 960, e, "Bar"); // 10:00–16:00 fits merged window
        verifier.verifyThat(ScheduleConstraintProvider::availability).given(e, a).penalizesBy(0);
    }

    // --------------------------------------------------------- overlapping

    @Test
    void overlappingShifts_penalizeOnePairOnce() {
        Employee e = availableAllDay(employee("e1", "Bar"), day(0));
        ShiftAssignment a = assignment("a1", day(0), 540, 720, e, "Bar");  // 09:00–12:00
        ShiftAssignment b = assignment("a2", day(0), 660, 900, e, "Bar");  // 11:00–15:00 overlaps
        verifier.verifyThat(ScheduleConstraintProvider::overlappingShifts).given(e, a, b).penalizesBy(1);
    }

    @Test
    void overlappingShifts_silentForBackToBack() {
        Employee e = availableAllDay(employee("e1", "Bar"), day(0));
        ShiftAssignment a = assignment("a1", day(0), 540, 720, e, "Bar");  // 09:00–12:00
        ShiftAssignment b = assignment("a2", day(0), 720, 900, e, "Bar");  // 12:00–15:00 touches
        verifier.verifyThat(ScheduleConstraintProvider::overlappingShifts).given(e, a, b).penalizesBy(0);
    }

    // ------------------------------------------------------------- min rest

    @Test
    void minRest_penalizesInsufficientGapBetweenShifts() {
        Employee e = availableAllDay(employee("e1", "Bar"), day(0));
        availableAllDay(e, day(1));
        e.getRules().add(rule("restHours", "min", 12));
        ShiftAssignment a = assignment("a1", day(0), 600, 1320, e, "Bar"); // ends 22:00
        ShiftAssignment b = assignment("a2", day(1), 360, 720, e, "Bar");  // next day 06:00 → 8h gap
        verifier.verifyThat(ScheduleConstraintProvider::minRestBetweenShifts).given(e, a, b).penalizesBy(1);
    }

    @Test
    void minRest_silentWhenGapIsEnough() {
        Employee e = availableAllDay(employee("e1", "Bar"), day(0));
        availableAllDay(e, day(1));
        e.getRules().add(rule("restHours", "min", 12));
        ShiftAssignment a = assignment("a1", day(0), 600, 1080, e, "Bar"); // ends 18:00
        ShiftAssignment b = assignment("a2", day(1), 480, 720, e, "Bar");  // next day 08:00 → 14h gap
        verifier.verifyThat(ScheduleConstraintProvider::minRestBetweenShifts).given(e, a, b).penalizesBy(0);
    }

    // ----------------------------------------------------------- hour caps

    @Test
    void maxHoursPerDay_penalizesOverageInMinutes() {
        Employee e = availableAllDay(employee("e1", "Bar"), day(0));
        e.getRules().add(rule("dayHours", "max", 8));
        // two non-overlapping shifts on the same day totalling 10h → 2h (120 min) over
        ShiftAssignment a = assignment("a1", day(0), 360, 660, e, "Bar");  // 06:00–11:00 (5h)
        ShiftAssignment b = assignment("a2", day(0), 660, 960, e, "Bar");  // 11:00–16:00 (5h)
        verifier.verifyThat(ScheduleConstraintProvider::maxHoursPerDay).given(e, a, b).penalizesBy(120);
    }

    @Test
    void maxHoursPerWeek_penalizesOverage() {
        Employee e = availableAllDay(employee("e1", "Bar"), day(0));
        availableAllDay(e, day(1));
        e.getRules().add(rule("weekHours", "max", 9));
        ShiftAssignment a = assignment("a1", day(0), 540, 900, e, "Bar"); // 6h
        ShiftAssignment b = assignment("a2", day(1), 540, 900, e, "Bar"); // 6h → 12h, 3h over
        verifier.verifyThat(ScheduleConstraintProvider::maxHoursPerWeek).given(e, a, b).penalizesBy(180);
    }

    @Test
    void minHoursPerDay_penalizesShortfallOnlyOnWorkedDays() {
        Employee e = availableAllDay(employee("e1", "Bar"), day(0));
        e.getRules().add(rule("dayHours", "min", 6));
        ShiftAssignment a = assignment("a1", day(0), 540, 780, e, "Bar"); // 4h worked, 2h short
        verifier.verifyThat(ScheduleConstraintProvider::minHoursPerDay).given(e, a).penalizesBy(120);
    }

    // ------------------------------------------------------- consec days

    @Test
    void maxConsecutiveDays_penalizesLongRun() {
        Employee e = employee("e1", "Bar");
        for (int i = 0; i <= 3; i++) availableAllDay(e, day(i));
        e.getRules().add(rule("consecDays", "max", 2));
        ShiftAssignment a = assignment("a1", day(0), 540, 660, e, "Bar");
        ShiftAssignment b = assignment("a2", day(1), 540, 660, e, "Bar");
        ShiftAssignment c = assignment("a3", day(2), 540, 660, e, "Bar");
        ShiftAssignment d = assignment("a4", day(3), 540, 660, e, "Bar"); // 4-day run, cap 2 → 2 over
        verifier.verifyThat(ScheduleConstraintProvider::maxConsecutiveDays).given(e, a, b, c, d).penalizesBy(2);
    }

    // -------------------------------------------------- overnight (end < start)

    @Test
    void overnightShift_countsPositiveHoursTowardsTheDailyLimit() {
        Employee e = employee("e1", "Bar");
        e.getRules().add(rule("dayHours", "max", 3));
        ShiftAssignment a = assignment("a1", day(0), 1320, 120, e, "Bar"); // 22:00–02:00 = 4h
        // 4h worked, cap 3h → 1h (60 min) over — not a negative contribution.
        verifier.verifyThat(ScheduleConstraintProvider::maxHoursPerDay).given(e, a).penalizesBy(60);
    }

    @Test
    void overnightShift_detectsOverlapAcrossMidnight() {
        Employee e = employee("e1", "Bar");
        ShiftAssignment a = assignment("a1", day(0), 1320, 120, e, "Bar"); // 22:00–02:00 (day 0→1)
        ShiftAssignment b = assignment("a2", day(1), 0, 180, e, "Bar");    // 00:00–03:00 next day
        verifier.verifyThat(ScheduleConstraintProvider::overlappingShifts).given(e, a, b).penalizesBy(1);
    }

    @Test
    void overnightShift_restGapIsMeasuredFromTheRealEnd() {
        Employee e = employee("e1", "Bar");
        e.getRules().add(rule("restHours", "min", 8));
        ShiftAssignment a = assignment("a1", day(0), 1320, 360, e, "Bar"); // 22:00–06:00 (ends day 1 06:00)
        ShiftAssignment b = assignment("a2", day(1), 600, 840, e, "Bar");  // 10:00–14:00 → only 4h rest
        verifier.verifyThat(ScheduleConstraintProvider::minRestBetweenShifts).given(e, a, b).penalizesBy(1);
    }

    // -------------------------------------------------------------- coverage

    @Test
    void coverage_rewardsEachStaffedSlot() {
        Employee e = availableAllDay(employee("e1", "Bar"), day(0));
        ShiftAssignment a = assignment("a1", day(0), 540, 660, e, "Bar");
        ShiftAssignment unstaffed = assignment("a2", day(0), 540, 660, null, "Bar");
        verifier.verifyThat(ScheduleConstraintProvider::coverage).given(e, a, unstaffed).rewardsWith(1);
    }

    // ------------------------------------------------------------ soft prefs

    @Test
    void preferredEmployee_rewardsPreferredAssignment() {
        Employee e = availableAllDay(employee("e1", "Bar"), day(0));
        ShiftAssignment a = assignment("a1", day(0), 540, 660, e, "Bar");
        a.setPreferredEmployeeIds(List.of("e1"));
        verifier.verifyThat(ScheduleConstraintProvider::preferredEmployee).given(e, a).rewardsWith(4);
    }

    @Test
    void preferredTimeBlock_rewardsHoursInsidePreferredWindow() {
        Employee e = employee("e1", "Bar");
        e.getBlocks().add(window("pref", day(0), 540, 1020)); // 09:00–17:00 preferred
        ShiftAssignment a = assignment("a1", day(0), 540, 900, e, "Bar"); // 6h all preferred
        verifier.verifyThat(ScheduleConstraintProvider::preferredTimeBlock).given(e, a).rewardsWith(6);
    }

    @Test
    void undesiredTimeBlock_penalizesHoursInsideUndesiredWindow() {
        Employee e = employee("e1", "Bar");
        e.getBlocks().add(window("undes", day(0), 540, 1020)); // 09:00–17:00 undesired (still available)
        ShiftAssignment a = assignment("a1", day(0), 540, 900, e, "Bar"); // 6h undesired
        verifier.verifyThat(ScheduleConstraintProvider::undesiredTimeBlock).given(e, a).penalizesBy(6);
    }

    @Test
    void balanceWorkload_penalizesCountSquared() {
        Employee e = availableAllDay(employee("e1", "Bar"), day(0));
        ShiftAssignment a = assignment("a1", day(0), 540, 600, e, "Bar");
        ShiftAssignment b = assignment("a2", day(0), 600, 660, e, "Bar");
        ShiftAssignment c = assignment("a3", day(0), 660, 720, e, "Bar");
        verifier.verifyThat(ScheduleConstraintProvider::balanceWorkload).given(e, a, b, c).penalizesBy(9); // 3^2
    }

    // ------------------------------------------------- boundary lookback (#47 Phase 2)
    // A worked shift from before the window is a fixed history fact: it counts towards
    // the aggregate/rest/consec limits at the boundary, but per-shift rules, coverage and
    // preferences ignore it, and a breach is only charged when a real window slot shares it.

    private static ShiftAssignment history(ShiftAssignment a) {
        a.setHistory(true);
        a.setPinned(true);
        return a;
    }

    @Test
    void minRest_countsHistoryShiftLeadingIntoTheWindow() {
        Employee e = employee("e1", "Bar");
        e.getRules().add(rule("restHours", "min", 12));
        ShiftAssignment past = history(assignment("h", day(-1), 600, 1320, e, "Bar")); // ends day(-1) 22:00
        ShiftAssignment win = assignment("a", day(0), 360, 720, e, "Bar");             // day(0) 06:00 → 8h rest
        verifier.verifyThat(ScheduleConstraintProvider::minRestBetweenShifts).given(e, past, win).penalizesBy(1);
    }

    @Test
    void minRest_silentBetweenTwoHistoryShifts() {
        Employee e = employee("e1", "Bar");
        e.getRules().add(rule("restHours", "min", 12));
        ShiftAssignment h1 = history(assignment("h1", day(-2), 600, 1320, e, "Bar")); // ends day(-2) 22:00
        ShiftAssignment h2 = history(assignment("h2", day(-1), 360, 720, e, "Bar"));  // day(-1) 06:00 → 8h
        verifier.verifyThat(ScheduleConstraintProvider::minRestBetweenShifts).given(e, h1, h2).penalizesBy(0);
    }

    @Test
    void maxHoursPerWeek_countsHistoryHoursInTheBoundaryWeek() {
        Employee e = employee("e1", "Bar");
        e.getRules().add(rule("weekHours", "max", 9));
        // Same ISO week (Mon-anchored): 6h history + 6h window = 12h, 3h (180 min) over.
        ShiftAssignment past = history(assignment("h", day(0), 540, 900, e, "Bar")); // Mon, 6h
        ShiftAssignment win = assignment("a", day(2), 540, 900, e, "Bar");           // Wed, 6h
        verifier.verifyThat(ScheduleConstraintProvider::maxHoursPerWeek).given(e, past, win).penalizesBy(180);
    }

    @Test
    void maxHoursPerWeek_silentForAPurelyHistoricalWeek() {
        Employee e = employee("e1", "Bar");
        e.getRules().add(rule("weekHours", "max", 9));
        ShiftAssignment h1 = history(assignment("h1", day(0), 540, 900, e, "Bar")); // 6h
        ShiftAssignment h2 = history(assignment("h2", day(2), 540, 900, e, "Bar")); // 6h → 12h, but no window slot
        verifier.verifyThat(ScheduleConstraintProvider::maxHoursPerWeek).given(e, h1, h2).penalizesBy(0);
    }

    @Test
    void maxConsecutiveDays_countsAHistoryRunLeadingIntoAWindowDay() {
        Employee e = employee("e1", "Bar");
        e.getRules().add(rule("consecDays", "max", 2));
        ShiftAssignment h1 = history(assignment("h1", day(-2), 540, 660, e, "Bar"));
        ShiftAssignment h2 = history(assignment("h2", day(-1), 540, 660, e, "Bar"));
        ShiftAssignment win = assignment("a", day(0), 540, 660, e, "Bar"); // 3-day run incl. window, cap 2 → 1 over
        verifier.verifyThat(ScheduleConstraintProvider::maxConsecutiveDays).given(e, h1, h2, win).penalizesBy(1);
    }

    @Test
    void maxConsecutiveDays_silentForAPurelyHistoricalRun() {
        Employee e = employee("e1", "Bar");
        e.getRules().add(rule("consecDays", "max", 2));
        // A 3-day historical run (no window day in it), plus a separate, isolated window day.
        ShiftAssignment h1 = history(assignment("h1", day(-3), 540, 660, e, "Bar"));
        ShiftAssignment h2 = history(assignment("h2", day(-2), 540, 660, e, "Bar"));
        ShiftAssignment h3 = history(assignment("h3", day(-1), 540, 660, e, "Bar"));
        ShiftAssignment win = assignment("a", day(1), 540, 660, e, "Bar"); // not adjacent to the run
        verifier.verifyThat(ScheduleConstraintProvider::maxConsecutiveDays).given(e, h1, h2, h3, win).penalizesBy(0);
    }

    @Test
    void coverage_ignoresHistorySlots() {
        Employee e = availableAllDay(employee("e1", "Bar"), day(0));
        ShiftAssignment past = history(assignment("h", day(-1), 540, 660, e, "Bar"));
        ShiftAssignment win = assignment("a", day(0), 540, 660, e, "Bar");
        verifier.verifyThat(ScheduleConstraintProvider::coverage).given(e, past, win).rewardsWith(1); // only the window slot
    }

    @Test
    void requiredSkills_ignoresHistorySlots() {
        Employee e = availableAllDay(employee("e1", "Floor"), day(-1));
        ShiftAssignment past = history(assignment("h", day(-1), 540, 660, e, "Bar")); // lacks Bar, but it's history
        verifier.verifyThat(ScheduleConstraintProvider::requiredSkills).given(e, past).penalizesBy(0);
    }
}
