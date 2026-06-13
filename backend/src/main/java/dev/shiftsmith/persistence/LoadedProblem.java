package dev.shiftsmith.persistence;

import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.Position;
import dev.shiftsmith.domain.Settings;

import java.util.List;
import java.util.Map;

/**
 * The whole problem read out of the normalized rows on boot, so the in-memory
 * {@code ScheduleService} (and the solver) can rehydrate. This is purely the shape of
 * a <em>read</em> — there is no document-shaped write or blob behind it any more
 * (issue #47, Phase 7): every write goes through the granular per-resource stores.
 */
public record LoadedProblem(List<Employee> employees, List<Position> positions,
                            Settings settings, Map<String, List<String>> overrides) {
}
