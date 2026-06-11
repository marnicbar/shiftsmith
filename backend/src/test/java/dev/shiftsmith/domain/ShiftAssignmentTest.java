package dev.shiftsmith.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static dev.shiftsmith.support.Fixtures.assignment;
import static dev.shiftsmith.support.Fixtures.employee;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Time arithmetic on {@link ShiftAssignment} (durations, week/month buckets and
 * minute-of-day) feeds directly into the hour-limit and availability constraints,
 * so it is verified independently here — including the midnight roll-over case.
 */
class ShiftAssignmentTest {

    private static final LocalDate MON = LocalDate.of(2026, 6, 1); // Monday

    @Test
    void durationHoursOfADaytimeShift() {
        ShiftAssignment a = assignment("a", MON, 540, 1020, null); // 09:00–17:00
        assertThat(a.getDurationHours()).isEqualTo(8.0);
        assertThat(a.getStartMinutes()).isEqualTo(540);
        assertThat(a.getEndMinutes()).isEqualTo(1020);
    }

    @Test
    void endAtMidnightRollsToNextDayButReportsAs1440() {
        ShiftAssignment a = assignment("a", MON, 1320, 1440, null); // 22:00–24:00
        assertThat(a.getEnd().toLocalDate()).isEqualTo(MON.plusDays(1));
        assertThat(a.getEndMinutes()).isEqualTo(1440);
        assertThat(a.getDurationHours()).isEqualTo(2.0);
    }

    @Test
    void overnightShiftWrapsEndPastMidnight() {
        ShiftAssignment a = assignment("a", MON, 1320, 120, null); // 22:00–02:00 next day
        assertThat(a.getEnd().toLocalDate()).isEqualTo(MON.plusDays(1));
        assertThat(a.getStartMinutes()).isEqualTo(1320);
        assertThat(a.getEndMinutes()).isEqualTo(1560);   // 02:00 expressed as 24:00 + 2h
        assertThat(a.getDurationHours()).isEqualTo(4.0);  // not negative
    }

    @Test
    void weekStartIsTheMondayOfTheShiftsWeek() {
        ShiftAssignment wed = assignment("a", MON.plusDays(2), 540, 600, null); // Wednesday
        assertThat(wed.getWeekStart()).isEqualTo(MON);
    }

    @Test
    void monthStartIsTheFirstOfTheShiftsMonth() {
        ShiftAssignment a = assignment("a", LocalDate.of(2026, 6, 18), 540, 600, null);
        assertThat(a.getMonthStart()).isEqualTo(LocalDate.of(2026, 6, 1));
    }

    @Test
    void isPreferredReflectsThePreferredEmployeeIds() {
        ShiftAssignment a = assignment("a", MON, 540, 600, null);
        a.setPreferredEmployeeIds(List.of("e1", "e2"));
        assertThat(a.isPreferred(employee("e1"))).isTrue();
        assertThat(a.isPreferred(employee("e3"))).isFalse();
        assertThat(a.isPreferred(null)).isFalse();
    }
}
