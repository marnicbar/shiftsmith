package dev.shiftsmith.service;

import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.Position;
import dev.shiftsmith.domain.Settings;
import dev.shiftsmith.domain.ShiftAssignment;
import dev.shiftsmith.domain.ShiftTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Expands recurring position/shift templates into concrete {@link ShiftAssignment}
 * slots over the configured solve horizon, applying any manual overrides as pins.
 *
 * Override map key is {@code "<templateId>@<isoDate>"} → ordered list of employee
 * ids (matching the frontend's overrides structure). A present key marks the whole
 * occurrence as manually controlled, so all of its slots are pinned.
 */
public final class ScheduleExpander {

    private ScheduleExpander() {}

    public static List<ShiftAssignment> expand(List<Position> positions, List<Employee> employees,
                                               Settings settings, Map<String, List<String>> overrides,
                                               LocalDate today) {
        Map<String, Employee> byId = new HashMap<>();
        for (Employee e : employees) byId.put(e.getId(), e);
        Map<String, List<String>> ov = overrides == null ? Map.of() : overrides;

        LocalDate start = settings.horizonStart(today);
        LocalDate end = settings.horizonEnd(today); // exclusive

        List<ShiftAssignment> out = new ArrayList<>();
        for (Position p : positions) {
            for (ShiftTemplate t : p.getShifts()) {
                for (LocalDate d = start; d.isBefore(end); d = d.plusDays(1)) {
                    if (!t.occursOn(d)) continue;
                    LocalDateTime st = d.atTime(t.getStart() / 60, t.getStart() % 60);
                    LocalDateTime en = t.getEnd() >= 1440
                            ? d.plusDays(1).atStartOfDay()
                            : d.atTime(t.getEnd() / 60, t.getEnd() % 60);

                    String key = t.getId() + "@" + d;
                    List<String> pins = ov.get(key);
                    int headcount = Math.max(1, t.getHeadcount());
                    for (int i = 0; i < headcount; i++) {
                        ShiftAssignment a = new ShiftAssignment(
                                key + "#" + i, p, t, i, d, st, en);
                        if (pins != null) {
                            a.setPinned(true);
                            if (i < pins.size()) a.setEmployee(byId.get(pins.get(i)));
                        }
                        out.add(a);
                    }
                }
            }
        }
        return out;
    }
}
