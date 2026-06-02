package dev.shiftsmith.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Block#occursOn} adds two behaviours on top of {@link Recurrence}:
 * a multi-day span (for vacation ranges) via {@code endDate}, and weekly
 * recurrence restricted to selected weekdays via {@code days}.
 */
class BlockOccursOnTest {

    private static final LocalDate MON = LocalDate.of(2026, 6, 1); // Monday

    private static Block vac(LocalDate date) {
        Block b = new Block();
        b.setType("vac");
        b.setDate(date);
        b.setAllDay(true);
        b.setRepeat("none");
        return b;
    }

    @Test
    void multiDayVacationCoversTheWholeInclusiveRange() {
        Block b = vac(MON);
        b.setEndDate(MON.plusDays(3)); // Mon–Thu
        assertThat(b.occursOn(MON.minusDays(1))).isFalse();
        assertThat(b.occursOn(MON)).isTrue();
        assertThat(b.occursOn(MON.plusDays(2))).isTrue();
        assertThat(b.occursOn(MON.plusDays(3))).isTrue();
        assertThat(b.occursOn(MON.plusDays(4))).isFalse();
    }

    @Test
    void multiDayVacationStillHonoursExcept() {
        Block b = vac(MON);
        b.setEndDate(MON.plusDays(3));
        b.setExcept(Set.of(MON.plusDays(1)));
        assertThat(b.occursOn(MON.plusDays(1))).isFalse();
        assertThat(b.occursOn(MON.plusDays(2))).isTrue();
    }

    @Test
    void noEndDateMeansSingleDay() {
        Block b = vac(MON);
        assertThat(b.occursOn(MON)).isTrue();
        assertThat(b.occursOn(MON.plusDays(1))).isFalse();
    }

    @Test
    void weeklyOnSelectedDaysRecursOnEachChosenWeekday() {
        Block b = new Block();
        b.setType("pref");
        b.setDate(MON);
        b.setStart(540);
        b.setEnd(1020);
        b.setRepeat("weekly");
        b.setDays(Set.of(0, 2)); // Mon, Wed
        assertThat(b.occursOn(MON)).isTrue();               // Mon
        assertThat(b.occursOn(MON.plusDays(1))).isFalse();  // Tue
        assertThat(b.occursOn(MON.plusDays(2))).isTrue();   // Wed
        assertThat(b.occursOn(MON.plusWeeks(1).plusDays(2))).isTrue(); // next Wed
    }
}
