package dev.shiftsmith.domain;

import dev.shiftsmith.support.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static dev.shiftsmith.support.Fixtures.MON;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Server-side rejection of out-of-range / malformed {@code PUT /api/problem}
 * payloads — the guard that stops a stale, replayed, or hand-crafted request from
 * persisting a document that makes the expander throw (DoS / boot-brick) before
 * the solver ever runs. Mirrors {@link DuplicateId}/{@link CalendarOverlap} as a
 * 400-able check the UI can't bypass.
 */
class ProblemValidationTest {

    private Settings settings() {
        return new Settings("week", 1);
    }

    private Optional<String> validate(List<Position> positions) {
        return ProblemValidation.firstError(List.of(), positions, settings());
    }

    private Position positionWith(ShiftTemplate shift) {
        Position p = Fixtures.position("p1", "Bar");
        p.getShifts().add(shift);
        return p;
    }

    @Test
    @DisplayName("rejects an employee with a missing id")
    void blankEmployeeIdRejected() {
        Employee e = Fixtures.employee("e1");
        e.setId("  ");
        assertThat(ProblemValidation.firstError(List.of(e), List.of(), settings())).isPresent();
    }

    @Test
    @DisplayName("rejects a position with a null id")
    void nullPositionIdRejected() {
        Position p = Fixtures.position("p1", "Bar");
        p.setId(null);
        assertThat(ProblemValidation.firstError(List.of(), List.of(p), settings())).isPresent();
    }

    @Test
    @DisplayName("rejects a shift with a blank id")
    void blankShiftIdRejected() {
        ShiftTemplate t = Fixtures.template("s1", MON, 600, 720, 1);
        t.setId("");
        assertThat(validate(List.of(positionWith(t)))).isPresent();
    }

    @Test
    @DisplayName("rejects a negative start time")
    void negativeStartRejected() {
        ShiftTemplate t = Fixtures.template("s1", MON, -100, 720, 1);
        Optional<String> err = validate(List.of(positionWith(t)));
        assertThat(err).isPresent();
        assertThat(err.get()).contains("start");
    }

    @Test
    @DisplayName("rejects a start time past the end of the day")
    void startPastDayRejected() {
        ShiftTemplate t = Fixtures.template("s1", MON, 1500, 600, 1);
        assertThat(validate(List.of(positionWith(t)))).isPresent();
    }

    @Test
    @DisplayName("rejects an end time past midnight")
    void endPastMidnightRejected() {
        ShiftTemplate t = Fixtures.template("s1", MON, 600, 1441, 1);
        Optional<String> err = validate(List.of(positionWith(t)));
        assertThat(err).isPresent();
        assertThat(err.get()).contains("end");
    }

    @Test
    @DisplayName("rejects a headcount below 1")
    void zeroHeadcountRejected() {
        ShiftTemplate t = Fixtures.template("s1", MON, 600, 720, 0);
        assertThat(validate(List.of(positionWith(t)))).isPresent();
    }

    @Test
    @DisplayName("rejects an absurd headcount")
    void hugeHeadcountRejected() {
        ShiftTemplate t = Fixtures.template("s1", MON, 600, 720, Integer.MAX_VALUE);
        Optional<String> err = validate(List.of(positionWith(t)));
        assertThat(err).isPresent();
        assertThat(err.get()).contains("headcount");
    }

    @Test
    @DisplayName("rejects a horizon count below 1")
    void zeroHorizonCountRejected() {
        assertThat(ProblemValidation.firstError(List.of(), List.of(), new Settings("month", 0))).isPresent();
    }

    @Test
    @DisplayName("rejects an absurd horizon count")
    void hugeHorizonCountRejected() {
        Optional<String> err = ProblemValidation.firstError(
                List.of(), List.of(), new Settings("month", 1_000_000));
        assertThat(err).isPresent();
        assertThat(err.get()).contains("Horizon");
    }

    @Test
    @DisplayName("accepts an overnight shift (end at or before start)")
    void overnightShiftAccepted() {
        // 22:00 → 06:00 wraps to the next day; this is a valid shift, not a bad payload.
        ShiftTemplate t = Fixtures.template("s1", MON, 1320, 360, 1);
        assertThat(validate(List.of(positionWith(t)))).isEmpty();
    }

    @Test
    @DisplayName("accepts an end exactly at midnight (1440)")
    void midnightEndAccepted() {
        ShiftTemplate t = Fixtures.template("s1", MON, 1320, 1440, 1);
        assertThat(validate(List.of(positionWith(t)))).isEmpty();
    }

    @Test
    @DisplayName("accepts a well-formed problem within all bounds")
    void validProblemAccepted() {
        Employee e = Fixtures.employee("e1");
        ShiftTemplate t = Fixtures.template("s1", MON, 480, 960, 3);
        assertThat(ProblemValidation.firstError(List.of(e), List.of(positionWith(t)), settings())).isEmpty();
    }

    @Test
    @DisplayName("tolerates null lists and null settings")
    void nullsAccepted() {
        assertThat(ProblemValidation.firstError(null, null, null)).isEmpty();
    }
}
