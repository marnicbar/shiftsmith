package dev.shiftsmith.domain;

import dev.shiftsmith.support.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static dev.shiftsmith.support.Fixtures.MON;
import static dev.shiftsmith.support.Fixtures.day;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Server-side rejection of overlapping calendar entries — the backend guard that
 * a malicious or buggy {@code PUT /api/problem} can't bypass.
 */
class CalendarOverlapTest {

    private Employee employeeWith(Block... blocks) {
        Employee e = Fixtures.employee("e1");
        for (Block b : blocks) e.getBlocks().add(b);
        return e;
    }

    private Position positionWith(ShiftTemplate... shifts) {
        Position p = Fixtures.position("p1", "Bar");
        for (ShiftTemplate s : shifts) p.getShifts().add(s);
        return p;
    }

    @Test
    @DisplayName("flags two availability blocks that share minutes on the same day")
    void overlappingBlocksRejected() {
        Employee e = employeeWith(
                Fixtures.window("pref", MON, 600, 720),
                Fixtures.window("undes", MON, 660, 780));
        Optional<String> conflict = CalendarOverlap.firstConflict(List.of(e), List.of());
        assertThat(conflict).isPresent();
        assertThat(conflict.get()).contains(MON.toString());
    }

    @Test
    @DisplayName("allows availability blocks that merely touch at the seam")
    void adjacentBlocksAllowed() {
        Employee e = employeeWith(
                Fixtures.window("pref", MON, 600, 720),
                Fixtures.window("undes", MON, 720, 840));
        assertThat(CalendarOverlap.firstConflict(List.of(e), List.of())).isEmpty();
    }

    @Test
    @DisplayName("allows blocks that are clear of each other")
    void disjointBlocksAllowed() {
        Employee e = employeeWith(
                Fixtures.window("pref", MON, 600, 660),
                Fixtures.window("undes", MON, 800, 900));
        assertThat(CalendarOverlap.firstConflict(List.of(e), List.of())).isEmpty();
    }

    @Test
    @DisplayName("ignores different days")
    void differentDaysAllowed() {
        Employee e = employeeWith(
                Fixtures.window("pref", MON, 600, 720),
                Fixtures.window("undes", day(1), 660, 780));
        assertThat(CalendarOverlap.firstConflict(List.of(e), List.of())).isEmpty();
    }

    @Test
    @DisplayName("exempts vacation / all-day entries even when the times collide")
    void vacationIsExempt() {
        Employee e = employeeWith(
                Fixtures.vacation(MON),
                Fixtures.window("pref", MON, 600, 720));
        assertThat(CalendarOverlap.firstConflict(List.of(e), List.of())).isEmpty();
    }

    @Test
    @DisplayName("flags overlapping shifts within a position")
    void overlappingShiftsRejected() {
        Position p = positionWith(
                Fixtures.template("s1", MON, 600, 720, 1),
                Fixtures.template("s2", MON, 660, 780, 1));
        Optional<String> conflict = CalendarOverlap.firstConflict(List.of(), List.of(p));
        assertThat(conflict).isPresent();
        assertThat(conflict.get()).contains("Bar");
    }

    @Test
    @DisplayName("catches a recurring block overlapping a later one-off occurrence")
    void recurringOverlapRejected() {
        Block weekly = Fixtures.window("pref", MON, 600, 720);
        weekly.setRepeat("weekly");
        Employee e = employeeWith(weekly, Fixtures.window("undes", day(7), 660, 780));
        assertThat(CalendarOverlap.firstConflict(List.of(e), List.of())).isPresent();
    }

    @Test
    @DisplayName("detects overnight spillover into the next morning")
    void overnightSpilloverRejected() {
        // Mon 23:00 → 02:00 overlaps a Tue 01:00 → 03:00 block
        Employee e = employeeWith(
                Fixtures.window("pref", MON, 1380, 120),
                Fixtures.window("undes", day(1), 60, 180));
        assertThat(CalendarOverlap.firstConflict(List.of(e), List.of())).isPresent();
    }

    @Test
    @DisplayName("passes a clean problem")
    void cleanProblemAccepted() {
        Employee e = employeeWith(Fixtures.window("pref", MON, 600, 720));
        Position p = positionWith(Fixtures.template("s1", MON, 800, 900, 1));
        assertThat(CalendarOverlap.firstConflict(List.of(e), List.of(p))).isEmpty();
    }
}
