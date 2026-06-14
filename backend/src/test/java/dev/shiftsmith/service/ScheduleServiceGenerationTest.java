package dev.shiftsmith.service;

import dev.shiftsmith.domain.Schedule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the solver-generation guard (#38): a best-solution event from a
 * superseded job must be ignored so a late/queued solution built on the previous
 * problem can't overwrite the current best. Drives the generation counter directly
 * (no Timefold/CDI), exercising the same {@code applyIfCurrent} the async consumers use.
 */
class ScheduleServiceGenerationTest {

    private static Schedule schedule() {
        return new Schedule(List.of(), List.of());
    }

    @Test
    void ignoresBestSolutionEventsFromASupersededJob() {
        ScheduleService service = new ScheduleService();

        long gen1 = service.nextSolverGeneration();
        Schedule first = schedule();
        assertThat(service.applyIfCurrent(gen1, first)).isTrue();   // current job → applied
        assertThat(service.getBestSolution()).isSameAs(first);

        // A new solve supersedes gen1.
        long gen2 = service.nextSolverGeneration();
        Schedule stale = schedule();
        assertThat(service.applyIfCurrent(gen1, stale)).isFalse();  // late event from old job → dropped
        assertThat(service.getBestSolution()).isSameAs(first);      // best is unchanged

        // The current job's event is still honoured.
        Schedule fresh = schedule();
        assertThat(service.applyIfCurrent(gen2, fresh)).isTrue();
        assertThat(service.getBestSolution()).isSameAs(fresh);
    }
}
