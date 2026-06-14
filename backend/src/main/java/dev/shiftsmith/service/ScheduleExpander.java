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
                    int startMin = t.getStart();
                    int endMin = t.getEnd();
                    LocalDateTime st = d.atTime(startMin / 60, startMin % 60);
                    // An overnight shift (end at or before start) — or one ending exactly
                    // at midnight (end >= 1440) — rolls its end into the next calendar day,
                    // so [st, en) stays a forward interval (mirrors CalendarOverlap's wrap).
                    LocalDateTime en;
                    if (endMin >= 1440) {
                        en = d.plusDays(1).atStartOfDay();
                    } else if (endMin <= startMin) {
                        en = d.plusDays(1).atTime(endMin / 60, endMin % 60);
                    } else {
                        en = d.atTime(endMin / 60, endMin % 60);
                    }

                    String key = t.getId() + "@" + d;
                    List<String> pins = ov.get(key);
                    int headcount = Math.max(1, t.getHeadcount());
                    for (int i = 0; i < headcount; i++) {
                        ShiftAssignment a = new ShiftAssignment(
                                key + "#" + i, p, t, i, d, st, en);
                        if (pins != null) {
                            // A present key marks the occurrence as manually controlled. Each
                            // slot is either pinned to the listed person, intentionally
                            // pinned-empty (the list is shorter than headcount, or the entry
                            // is blank), or — when the listed person has since been deleted —
                            // left unpinned so the solver can refill it instead of being a
                            // null-employee pin that blocks the slot forever (#39).
                            String pinnedId = i < pins.size() ? pins.get(i) : null;
                            if (pinnedId == null || pinnedId.isBlank()) {
                                a.setPinned(true); // intentional pinned-empty slot
                            } else {
                                Employee e = byId.get(pinnedId);
                                if (e != null) {
                                    a.setEmployee(e);
                                    a.setPinned(true);
                                }
                                // else: pinned person was deleted — leave the slot fillable.
                            }
                        }
                        out.add(a);
                    }
                }
            }
        }
        return out;
    }
}
