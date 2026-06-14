package dev.shiftsmith.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static dev.shiftsmith.support.Fixtures.availableAllDay;
import static dev.shiftsmith.support.Fixtures.employee;
import static dev.shiftsmith.support.Fixtures.rule;
import static dev.shiftsmith.support.Fixtures.vacation;
import static dev.shiftsmith.support.Fixtures.window;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure tests of {@link Employee}'s availability/preference maths and the
 * time-varying working-time limit resolution — the logic the hard availability
 * and soft preference constraints sit on top of.
 */
class EmployeeAvailabilityTest {

    private static final LocalDate D = LocalDate.of(2026, 6, 1);

    // --- availability windows -------------------------------------------

    @Test
    void emptyCalendarMeansUnavailable() {
        Employee e = employee("e1");
        assertThat(e.availableWindows(D)).isEmpty();
        assertThat(e.isAvailableFor(D, 540, 1020)).isFalse();
    }

    @Test
    void shiftMustFitEntirelyWithinAWindow() {
        Employee e = employee("e1");
        e.getBlocks().add(window("pref", D, 480, 1080)); // 08:00–18:00
        assertThat(e.isAvailableFor(D, 540, 1020)).isTrue();  // 09:00–17:00 fits
        assertThat(e.isAvailableFor(D, 420, 1020)).isFalse(); // starts 07:00, before window
        assertThat(e.isAvailableFor(D, 540, 1140)).isFalse(); // ends 19:00, after window
    }

    @Test
    void preferredAndUndesiredBothDefineAvailabilityAndMergeWhenAdjacent() {
        Employee e = employee("e1");
        e.getBlocks().add(window("pref", D, 480, 720));    // 08:00–12:00
        e.getBlocks().add(window("undes", D, 720, 1080));  // 12:00–18:00 touches → one window
        assertThat(e.availableWindows(D)).hasSize(1);
        assertThat(e.isAvailableFor(D, 600, 960)).isTrue(); // 10:00–16:00 spans the seam
    }

    @Test
    void disjointWindowsStaySeparate() {
        Employee e = employee("e1");
        e.getBlocks().add(window("pref", D, 480, 600));   // 08:00–10:00
        e.getBlocks().add(window("pref", D, 720, 1080));  // 12:00–18:00
        assertThat(e.availableWindows(D)).hasSize(2);
        assertThat(e.isAvailableFor(D, 540, 960)).isFalse(); // 09:00–16:00 crosses the gap
    }

    @Test
    void overnightWindowWrapsPastMidnightInsteadOfBeingDropped() {
        Employee e = employee("e1");
        e.getBlocks().add(window("pref", D, 1320, 120)); // 22:00 → 02:00 next day
        // The window is usable, not dropped: it spans [1320, 1560] in wrapped minutes.
        assertThat(e.availableWindows(D)).hasSize(1);
        assertThat(e.isAvailableFor(D, 1320, 1560)).isTrue();  // 22:00–02:00 fits exactly
        assertThat(e.isAvailableFor(D, 1380, 1500)).isTrue();  // 23:00–01:00 fits inside
        assertThat(e.isAvailableFor(D, 1260, 1560)).isFalse(); // starts 21:00, before window
    }

    @Test
    void twoAdjacentDayWindowsAcrossMidnightCoverAnOvernightShift() {
        // Overnight availability can't be entered as one block, so it's expressed as
        // two adjacent day windows: one reaching midnight, one starting at midnight.
        Employee e = employee("e1");
        e.getBlocks().add(window("pref", D, 1080, 1440));          // 18:00–24:00
        e.getBlocks().add(window("pref", D.plusDays(1), 0, 360));  // next day 00:00–06:00
        // 18:00–06:00 shift: end is 06:00 next day → 1800 in wrapped minutes.
        assertThat(e.isAvailableFor(D, 1080, 1800)).isTrue();
        assertThat(e.isAvailableFor(D, 1020, 1800)).isFalse(); // starts 17:00, before the window
        assertThat(e.isAvailableFor(D, 1080, 1860)).isFalse(); // ends 07:00, past the next-day window
    }

    @Test
    void availabilityOnlyOnTheStartDayDoesNotCoverThePostMidnightTail() {
        Employee e = employee("e1");
        e.getBlocks().add(window("pref", D, 1080, 1440)); // 18:00–24:00, nothing the next day
        assertThat(e.isAvailableFor(D, 1080, 1560)).isFalse(); // 18:00–02:00 spills past midnight
    }

    @Test
    void allDayOnBothSidesOfMidnightCoversAnOvernightShift() {
        Employee e = employee("e1");
        availableAllDay(e, D);
        availableAllDay(e, D.plusDays(1));
        assertThat(e.isAvailableFor(D, 1320, 1560)).isTrue(); // 22:00–02:00
    }

    @Test
    void overnightPreferredMinutesCountTheWrappedOverlap() {
        Employee e = employee("e1");
        e.getBlocks().add(window("pref", D, 1320, 120)); // 22:00 → 02:00 preferred
        // shift 23:00–02:00 → 23:00–02:00 (180 min) all inside the wrapped window
        assertThat(e.preferredMinutes(D, 1380, 1560)).isEqualTo(180);
    }

    // --- preferred / undesired minutes ----------------------------------

    @Test
    void preferredMinutesCountsOnlyTheOverlap() {
        Employee e = employee("e1");
        e.getBlocks().add(window("pref", D, 540, 720)); // 09:00–12:00 preferred
        // shift 10:00–13:00 → only 10:00–12:00 (120 min) overlaps
        assertThat(e.preferredMinutes(D, 600, 780)).isEqualTo(120);
    }

    @Test
    void undesiredMinutesCountsOnlyTheOverlap() {
        Employee e = employee("e1");
        e.getBlocks().add(window("undes", D, 1080, 1320)); // 18:00–22:00 undesired
        assertThat(e.undesiredMinutes(D, 1200, 1440)).isEqualTo(120); // 20:00–22:00
    }

    // --- vacation -------------------------------------------------------

    @Test
    void vacationBlockMakesTheWholeDayOff() {
        Employee e = employee("e1");
        e.getBlocks().add(vacation(D));
        assertThat(e.isOnVacation(D)).isTrue();
        assertThat(e.isOnVacation(D.plusDays(1))).isFalse();
    }

    // --- time-varying limits --------------------------------------------

    @Test
    void maxLimitTakesTheTightestCeiling() {
        Employee e = employee("e1");
        e.getRules().add(rule("weekHours", "max", 48));
        e.getRules().add(rule("weekHours", "max", 40));
        assertThat(e.maxLimit("weekHours", D)).isEqualTo(40);
    }

    @Test
    void minLimitTakesTheTightestFloor() {
        Employee e = employee("e1");
        e.getRules().add(rule("weekHours", "min", 10));
        e.getRules().add(rule("weekHours", "min", 20));
        assertThat(e.minLimit("weekHours", D)).isEqualTo(20);
    }

    @Test
    void absentLimitIsNull() {
        Employee e = employee("e1");
        assertThat(e.maxLimit("weekHours", D)).isNull();
        assertThat(e.preferred("weekHours", D)).isNull();
    }
}
