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
}
