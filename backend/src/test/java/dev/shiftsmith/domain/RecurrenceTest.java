package dev.shiftsmith.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Recurrence} is the backend port of the frontend's {@code occursOn} logic;
 * the solver must expand templates/blocks exactly as the UI previews them, so these
 * cases pin down that shared contract.
 */
class RecurrenceTest {

    private static final LocalDate MON = LocalDate.of(2026, 6, 1); // Monday

    @Test
    void nullAnchorOrDateNeverOccurs() {
        assertThat(Recurrence.occursOn(null, "daily", MON, null, null)).isFalse();
        assertThat(Recurrence.occursOn(MON, "daily", null, null, null)).isFalse();
    }

    @Test
    void noneOccursOnlyOnAnchor() {
        assertThat(Recurrence.occursOn(MON, "none", MON, null, null)).isTrue();
        assertThat(Recurrence.occursOn(MON, "none", MON.plusDays(1), null, null)).isFalse();
    }

    @Test
    void nullRepeatTreatedAsNone() {
        assertThat(Recurrence.occursOn(MON, null, MON, null, null)).isTrue();
        assertThat(Recurrence.occursOn(MON, null, MON.plusDays(1), null, null)).isFalse();
    }

    @Test
    void dailyOccursOnAndAfterAnchorOnly() {
        assertThat(Recurrence.occursOn(MON, "daily", MON.minusDays(1), null, null)).isFalse();
        assertThat(Recurrence.occursOn(MON, "daily", MON, null, null)).isTrue();
        assertThat(Recurrence.occursOn(MON, "daily", MON.plusDays(10), null, null)).isTrue();
    }

    @Test
    void weeklyMatchesSameWeekdayOnOrAfterAnchor() {
        assertThat(Recurrence.occursOn(MON, "weekly", MON.plusWeeks(2), null, null)).isTrue();    // Monday
        assertThat(Recurrence.occursOn(MON, "weekly", MON.plusDays(1), null, null)).isFalse();    // Tuesday
        assertThat(Recurrence.occursOn(MON, "weekly", MON.minusWeeks(1), null, null)).isFalse();  // before anchor
    }

    @Test
    void untilIsInclusiveUpperBound() {
        LocalDate until = MON.plusDays(3);
        assertThat(Recurrence.occursOn(MON, "daily", until, until, null)).isTrue();
        assertThat(Recurrence.occursOn(MON, "daily", until.plusDays(1), until, null)).isFalse();
    }

    @Test
    void exceptDatesAreSkipped() {
        LocalDate skip = MON.plusDays(2);
        assertThat(Recurrence.occursOn(MON, "daily", skip, null, Set.of(skip))).isFalse();
        assertThat(Recurrence.occursOn(MON, "daily", skip.plusDays(1), null, Set.of(skip))).isTrue();
    }

    @Test
    void weeklyWithSelectedDaysMatchesAnyChosenWeekday() {
        // anchor Monday; repeat on Mon(0), Wed(2), Fri(4)
        Set<Integer> days = Set.of(0, 2, 4);
        assertThat(Recurrence.occursOn(MON, "weekly", MON, null, null, days)).isTrue();              // Mon
        assertThat(Recurrence.occursOn(MON, "weekly", MON.plusDays(1), null, null, days)).isFalse(); // Tue
        assertThat(Recurrence.occursOn(MON, "weekly", MON.plusDays(2), null, null, days)).isTrue();  // Wed
        assertThat(Recurrence.occursOn(MON, "weekly", MON.plusDays(4), null, null, days)).isTrue();  // Fri
        assertThat(Recurrence.occursOn(MON, "weekly", MON.plusWeeks(1).plusDays(2), null, null, days)).isTrue(); // next Wed
    }

    @Test
    void weeklyWithSelectedDaysStillRespectsAnchorAndBounds() {
        Set<Integer> days = Set.of(0, 2, 4);
        assertThat(Recurrence.occursOn(MON, "weekly", MON.minusDays(3), null, null, days)).isFalse(); // before anchor (Fri prior)
        LocalDate until = MON.plusDays(2); // Wed
        assertThat(Recurrence.occursOn(MON, "weekly", MON.plusDays(4), until, null, days)).isFalse(); // Fri after until
    }

    @Test
    void emptyOrNullDaysFallsBackToAnchorWeekday() {
        assertThat(Recurrence.occursOn(MON, "weekly", MON.plusWeeks(1), null, null, Set.of())).isTrue();
        assertThat(Recurrence.occursOn(MON, "weekly", MON.plusDays(1), null, null, Set.of())).isFalse();
        assertThat(Recurrence.occursOn(MON, "weekly", MON.plusWeeks(1), null, null, null)).isTrue();
    }
}
