package dev.shiftsmith.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The solve window drives which slots get expanded, so {@link Settings#horizonEnd}
 * is pinned down here against a fixed "today" (Wednesday 2026-06-03).
 */
class SettingsHorizonTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 3); // Wednesday

    @Test
    void horizonStartIsToday() {
        assertThat(new Settings("week", 1).horizonStart(TODAY)).isEqualTo(TODAY);
    }

    @Test
    void dayWindowCoversTodayPlusCount() {
        // today and tomorrow → exclusive end is the day after tomorrow
        assertThat(new Settings("day", 1).horizonEnd(TODAY)).isEqualTo(LocalDate.of(2026, 6, 5));
        assertThat(new Settings("day", 3).horizonEnd(TODAY)).isEqualTo(LocalDate.of(2026, 6, 7));
    }

    @Test
    void weekWindowEndsAtMondayAfterTheCountedWeeks() {
        // this week + next: Monday of this week is 06-01, end = 06-01 + 2 weeks
        assertThat(new Settings("week", 1).horizonEnd(TODAY)).isEqualTo(LocalDate.of(2026, 6, 15));
        assertThat(new Settings("week", 2).horizonEnd(TODAY)).isEqualTo(LocalDate.of(2026, 6, 22));
    }

    @Test
    void monthWindowEndsAtFirstOfMonthAfterTheCountedMonths() {
        assertThat(new Settings("month", 1).horizonEnd(TODAY)).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(new Settings("month", 2).horizonEnd(TODAY)).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    void nullUnitDefaultsToWeek() {
        assertThat(new Settings(null, 1).horizonEnd(TODAY)).isEqualTo(LocalDate.of(2026, 6, 15));
    }

    @Test
    void nonPositiveCountIsClampedToOne() {
        assertThat(new Settings("week", 0).horizonEnd(TODAY))
                .isEqualTo(new Settings("week", 1).horizonEnd(TODAY));
        assertThat(new Settings("day", -5).horizonEnd(TODAY))
                .isEqualTo(new Settings("day", 1).horizonEnd(TODAY));
    }
}
