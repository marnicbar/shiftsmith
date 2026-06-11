package dev.shiftsmith.solver;

import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.Position;
import dev.shiftsmith.domain.Schedule;
import dev.shiftsmith.domain.Settings;
import dev.shiftsmith.domain.ShiftAssignment;
import dev.shiftsmith.domain.ShiftTemplate;
import dev.shiftsmith.service.ScheduleExpander;
import dev.shiftsmith.support.SolverHarness;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.shiftsmith.support.Fixtures.MON;
import static dev.shiftsmith.support.Fixtures.availableAllDay;
import static dev.shiftsmith.support.Fixtures.employee;
import static dev.shiftsmith.support.Fixtures.position;
import static dev.shiftsmith.support.Fixtures.rule;
import static dev.shiftsmith.support.Fixtures.template;
import static dev.shiftsmith.support.Fixtures.vacation;
import static dev.shiftsmith.support.Fixtures.window;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * High-level, end-to-end solver tests. Each builds a small problem through the real
 * expansion + constraint stack, solves it, and asserts the outcome.
 *
 * <p>Every scenario is designed to have a <em>unique</em> optimal solution (one
 * employee can satisfy each slot, or a slot is impossible to fill) so the assertion
 * is deterministic — the solver runs in reproducible mode, but a problem with several
 * equally-good answers would still be ambiguous to assert against.
 *
 * <p>Score conventions: a feasible solution has {@code hardScore == 0}; medium score
 * equals the number of staffed slots (coverage).
 */
class ScheduleSolverTest {

    private final Settings window = new Settings("week", 1);

    private Schedule build(List<Position> positions, List<Employee> employees) {
        List<ShiftAssignment> slots = ScheduleExpander.expand(positions, employees, window, Map.of(), MON);
        return new Schedule(new ArrayList<>(employees), slots);
    }

    private Position positionWith(String id, String name, ShiftTemplate... templates) {
        Position p = position(id, name);
        p.getShifts().addAll(List.of(templates));
        return p;
    }

    private ShiftAssignment only(Schedule s) {
        assertThat(s.getAssignments()).hasSize(1);
        return s.getAssignments().get(0);
    }

    private long staffed(Schedule s) {
        return s.getAssignments().stream().filter(a -> a.getEmployee() != null).count();
    }

    // ===================================================================
    //  Positive: the expected employee is assigned
    // ===================================================================

    @Test
    void assignsTheOnlyEmployeeWhoHasTheRequiredSkill() {
        ShiftTemplate bar = template("bar", MON, 1020, 1440, 1, "Bar"); // 17:00–24:00
        Employee alice = availableAllDay(employee("alice", "Bar", "Floor"), MON);
        Employee bob = availableAllDay(employee("bob", "Floor"), MON); // no Bar skill

        Schedule solved = SolverHarness.solve(build(
                List.of(positionWith("p", "Bar", bar)), List.of(alice, bob)));

        assertThat(solved.getScore().hardScore()).isZero();
        assertThat(only(solved).getEmployee().getId()).isEqualTo("alice");
        assertThat(solved.getScore().mediumScore()).isEqualTo(1); // one slot covered
    }

    @Test
    void skillsForceAUniqueOneToOneMatching() {
        ShiftTemplate barShift = template("bar", MON, 1020, 1440, 1, "Bar");
        ShiftTemplate kitchenShift = template("kit", MON, 420, 720, 1, "Kitchen");
        Employee alice = availableAllDay(employee("alice", "Bar"), MON);
        Employee bob = availableAllDay(employee("bob", "Kitchen"), MON);

        Schedule solved = SolverHarness.solve(build(
                List.of(positionWith("p1", "Bar", barShift), positionWith("p2", "Kitchen", kitchenShift)),
                List.of(alice, bob)));

        assertThat(solved.getScore().hardScore()).isZero();
        assertThat(staffed(solved)).isEqualTo(2);
        assertThat(employeeOn(solved, "bar")).isEqualTo("alice");
        assertThat(employeeOn(solved, "kit")).isEqualTo("bob");
    }

    @Test
    void prefersThePreferredEmployeeWhenBothAreFeasible() {
        ShiftTemplate bar = template("bar", MON, 1020, 1440, 1, "Bar");
        bar.setPreferred(List.of("bob")); // bob is the shift's preferred employee
        Employee alice = availableAllDay(employee("alice", "Bar"), MON);
        Employee bob = availableAllDay(employee("bob", "Bar"), MON);

        Schedule solved = SolverHarness.solve(build(
                List.of(positionWith("p", "Bar", bar)), List.of(alice, bob)));

        assertThat(solved.getScore().hardScore()).isZero();
        assertThat(only(solved).getEmployee().getId()).isEqualTo("bob");
    }

    @Test
    void staffsAnOvernightShiftThatFitsAnOvernightAvailabilityWindow() {
        ShiftTemplate night = template("night", MON, 1320, 120, 1, "Bar"); // 22:00–02:00
        Employee alice = employee("alice", "Bar");
        alice.getBlocks().add(window("pref", MON, 1200, 360)); // 20:00 → 06:00 available

        Schedule solved = SolverHarness.solve(build(
                List.of(positionWith("p", "Bar", night)), List.of(alice)));

        assertThat(solved.getScore().hardScore()).isZero();
        assertThat(only(solved).getEmployee().getId()).isEqualTo("alice");
        assertThat(solved.getScore().mediumScore()).isEqualTo(1);
    }

    @Test
    void overnightShiftRespectsTheDailyHourCapInsteadOfReportingFalselyFeasible() {
        // Regression: an overnight shift used to subtract negative minutes, so the
        // solver would happily staff it past a tight hour cap while reporting 0 hard.
        ShiftTemplate night = template("night", MON, 1320, 120, 1, "Bar"); // 22:00–02:00 = 4h
        Employee alice = employee("alice", "Bar");
        alice.getBlocks().add(window("pref", MON, 1200, 360)); // available across midnight
        alice.getRules().add(rule("dayHours", "max", 3)); // 4h shift can never fit a 3h cap

        Schedule solved = SolverHarness.solve(build(
                List.of(positionWith("p", "Bar", night)), List.of(alice)));

        assertThat(solved.getScore().hardScore()).isZero();
        assertThat(only(solved).getEmployee()).isNull();
    }

    // ===================================================================
    //  Negative: impossible to staff → slot stays empty, still feasible
    // ===================================================================

    @Test
    void leavesSlotUnassignedWhenNobodyHasTheSkill() {
        ShiftTemplate bar = template("bar", MON, 1020, 1440, 1, "Bar");
        Employee alice = availableAllDay(employee("alice", "Floor"), MON);
        Employee bob = availableAllDay(employee("bob", "Kitchen"), MON);

        Schedule solved = SolverHarness.solve(build(
                List.of(positionWith("p", "Bar", bar)), List.of(alice, bob)));

        // No hard rule may be broken: the optimum is to leave the slot empty.
        assertThat(solved.getScore().hardScore()).isZero();
        assertThat(only(solved).getEmployee()).isNull();
        assertThat(staffed(solved)).isZero();
    }

    @Test
    void leavesSlotUnassignedWhenTheOnlyCandidateIsOnVacation() {
        ShiftTemplate bar = template("bar", MON, 1020, 1440, 1, "Bar");
        Employee alice = availableAllDay(employee("alice", "Bar"), MON);
        alice.getBlocks().add(vacation(MON));

        Schedule solved = SolverHarness.solve(build(
                List.of(positionWith("p", "Bar", bar)), List.of(alice)));

        assertThat(solved.getScore().hardScore()).isZero();
        assertThat(only(solved).getEmployee()).isNull();
    }

    @Test
    void leavesSlotUnassignedWhenAssigningWouldBreakTheDailyHourLimit() {
        ShiftTemplate longShift = template("long", MON, 480, 1020, 1, "Bar"); // 9h
        Employee alice = availableAllDay(employee("alice", "Bar"), MON);
        alice.getRules().add(rule("dayHours", "max", 4)); // 9h shift can never fit a 4h cap

        Schedule solved = SolverHarness.solve(build(
                List.of(positionWith("p", "Bar", longShift)), List.of(alice)));

        assertThat(solved.getScore().hardScore()).isZero();
        assertThat(only(solved).getEmployee()).isNull();
    }

    @Test
    void cannotDoubleBookTheSoleCandidateForOverlappingShifts() {
        ShiftTemplate morning = template("am", MON, 540, 780, 1, "Bar"); // 09:00–13:00
        ShiftTemplate overlap = template("pm", MON, 720, 960, 1, "Bar"); // 12:00–16:00 overlaps
        Employee alice = availableAllDay(employee("alice", "Bar"), MON);

        Schedule solved = SolverHarness.solve(build(
                List.of(positionWith("p", "Bar", morning, overlap)), List.of(alice)));

        // Only one of the two overlapping slots can be staffed without a hard violation.
        assertThat(solved.getScore().hardScore()).isZero();
        assertThat(staffed(solved)).isEqualTo(1);
    }

    // --- helpers --------------------------------------------------------

    private String employeeOn(Schedule s, String templateId) {
        return s.getAssignments().stream()
                .filter(a -> a.getShiftTemplateId().equals(templateId))
                .map(a -> a.getEmployee() == null ? null : a.getEmployee().getId())
                .findFirst().orElse(null);
    }
}
