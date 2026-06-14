package dev.shiftsmith.service;

import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.Position;
import dev.shiftsmith.domain.Settings;
import dev.shiftsmith.domain.ShiftAssignment;
import dev.shiftsmith.domain.ShiftTemplate;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static dev.shiftsmith.support.Fixtures.MON;
import static dev.shiftsmith.support.Fixtures.employee;
import static dev.shiftsmith.support.Fixtures.position;
import static dev.shiftsmith.support.Fixtures.template;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ScheduleExpander} turns recurring templates (+ overrides) into the concrete
 * slots the solver sees. These tests fix the horizon and "today" so the expansion is
 * fully deterministic.
 */
class ScheduleExpanderTest {

    private final Settings weekWindow = new Settings("week", 1);

    private Position positionWith(ShiftTemplate... templates) {
        Position p = position("p1", "Front Desk");
        p.getShifts().addAll(List.of(templates));
        return p;
    }

    @Test
    void oneSlotPerHeadcountPerOccurrence() {
        ShiftTemplate t = template("t1", MON, 540, 1020, 3, "Reception"); // headcount 3, repeat none
        Position p = positionWith(t);

        List<ShiftAssignment> slots = ScheduleExpander.expand(
                List.of(p), List.of(), weekWindow, Map.of(), MON);

        assertThat(slots).hasSize(3);
        assertThat(slots).allSatisfy(s -> {
            assertThat(s.getDate()).isEqualTo(MON);
            assertThat(s.getShiftTemplateId()).isEqualTo("t1");
            assertThat(s.getRequiredSkills()).containsExactly("Reception");
            assertThat(s.getEmployee()).isNull();
            assertThat(s.isPinned()).isFalse();
        });
        assertThat(slots).extracting(ShiftAssignment::getSlotIndex).containsExactlyInAnyOrder(0, 1, 2);
    }

    @Test
    void slotIdsAreStableAndUnique() {
        ShiftTemplate t = template("t1", MON, 540, 1020, 2, "Reception");
        List<ShiftAssignment> slots = ScheduleExpander.expand(
                List.of(positionWith(t)), List.of(), weekWindow, Map.of(), MON);
        assertThat(slots).extracting(ShiftAssignment::getId)
                .containsExactly("t1@" + MON + "#0", "t1@" + MON + "#1");
    }

    @Test
    void onlyOccurrencesInsideTheHorizonAreExpanded() {
        // weekly template anchored on MON; week window = this week + next → two occurrences
        ShiftTemplate weekly = template("t1", MON, 540, 1020, 1, "Reception");
        weekly.setRepeat("weekly");
        List<ShiftAssignment> slots = ScheduleExpander.expand(
                List.of(positionWith(weekly)), List.of(), weekWindow, Map.of(), MON);
        assertThat(slots).extracting(ShiftAssignment::getDate)
                .containsExactly(MON, MON.plusWeeks(1));
    }

    @Test
    void overnightTemplateEndRollsToNextDay() {
        ShiftTemplate t = template("t1", MON, 1320, 1440, 1, "Bar"); // 22:00–24:00
        List<ShiftAssignment> slots = ScheduleExpander.expand(
                List.of(positionWith(t)), List.of(), weekWindow, Map.of(), MON);
        assertThat(slots).hasSize(1);
        assertThat(slots.get(0).getEnd()).isEqualTo(MON.plusDays(1).atStartOfDay());
    }

    @Test
    void overnightTemplateRollsEndIntoTheNextDay() {
        ShiftTemplate t = template("t1", MON, 1320, 120, 1, "Bar"); // 22:00–02:00
        List<ShiftAssignment> slots = ScheduleExpander.expand(
                List.of(positionWith(t)), List.of(), weekWindow, Map.of(), MON);
        assertThat(slots).hasSize(1);
        ShiftAssignment a = slots.get(0);
        assertThat(a.getStart()).isEqualTo(MON.atTime(22, 0));
        assertThat(a.getEnd()).isEqualTo(MON.plusDays(1).atTime(2, 0)); // forward interval
        assertThat(a.getDurationHours()).isEqualTo(4.0);
    }

    @Test
    void overridesPinTheWholeOccurrenceAndAssignListedEmployees() {
        ShiftTemplate t = template("t1", MON, 540, 1020, 2, "Reception");
        Employee mei = employee("mei", "Reception");
        Map<String, List<String>> overrides = Map.of("t1@" + MON, List.of("mei"));

        List<ShiftAssignment> slots = ScheduleExpander.expand(
                List.of(positionWith(t)), List.of(mei), weekWindow, overrides, MON);

        assertThat(slots).hasSize(2);
        assertThat(slots).allMatch(ShiftAssignment::isPinned);
        // first slot gets the listed employee, the second is pinned-but-empty
        assertThat(slots.get(0).getEmployee()).isEqualTo(mei);
        assertThat(slots.get(1).getEmployee()).isNull();
    }

    @Test
    void pinToASinceDeletedEmployeeLeavesTheSlotFillable() {
        // The occurrence is pinned to "ghost", who no longer exists in the roster.
        ShiftTemplate t = template("t1", MON, 540, 1020, 1, "Reception");
        Map<String, List<String>> overrides = Map.of("t1@" + MON, List.of("ghost"));

        List<ShiftAssignment> slots = ScheduleExpander.expand(
                List.of(positionWith(t)), List.of(), weekWindow, overrides, MON);

        assertThat(slots).hasSize(1);
        // Not a null-employee pin that blocks the slot forever — the solver can refill it.
        assertThat(slots.get(0).isPinned()).isFalse();
        assertThat(slots.get(0).getEmployee()).isNull();
    }

    @Test
    void aDeletedPinDoesNotDisturbTheOtherPinnedSlots() {
        // headcount 2, pinned to [mei, ghost]; mei stays, ghost was deleted.
        ShiftTemplate t = template("t1", MON, 540, 1020, 2, "Reception");
        Employee mei = employee("mei", "Reception");
        Map<String, List<String>> overrides = Map.of("t1@" + MON, List.of("mei", "ghost"));

        List<ShiftAssignment> slots = ScheduleExpander.expand(
                List.of(positionWith(t)), List.of(mei), weekWindow, overrides, MON);

        assertThat(slots).hasSize(2);
        // slot 0 keeps its real pin; slot 1's deleted pin is dropped (not shifted onto mei).
        assertThat(slots.get(0).getEmployee()).isEqualTo(mei);
        assertThat(slots.get(0).isPinned()).isTrue();
        assertThat(slots.get(1).getEmployee()).isNull();
        assertThat(slots.get(1).isPinned()).isFalse();
    }

    @Test
    void headcountIsAtLeastOneEvenIfMisconfigured() {
        ShiftTemplate t = template("t1", MON, 540, 1020, 0, "Reception");
        List<ShiftAssignment> slots = ScheduleExpander.expand(
                List.of(positionWith(t)), List.of(), weekWindow, Map.of(), MON);
        assertThat(slots).hasSize(1);
    }

    @Test
    void noTemplatesProducesNoSlots() {
        assertThat(ScheduleExpander.expand(List.of(position("p", "Empty")), List.of(),
                weekWindow, Map.of(), MON)).isEmpty();
    }
}
