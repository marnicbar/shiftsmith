package dev.shiftsmith.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Server-side guard against duplicate entity IDs in a {@code PUT /api/problem} payload.
 *
 * <p>The frontend mints every ID and the backend stores it verbatim — it never
 * assigns IDs itself ({@code setId} is only a Jackson setter). So a buggy or stale
 * client can send the same ID twice: e.g. a reloaded tab that re-mints {@code e1}
 * on top of the {@code e1} it loaded. Edits then match records by {@code id ==},
 * so a collision silently corrupts the wrong record. This mirrors
 * {@link CalendarOverlap} as a 400-able check the UI can't bypass.
 *
 * <p>Every ID must be unique across the whole problem — employees, their
 * blocks/rules/changes, positions, and their shift templates all share one global
 * ID space, matching how the IDs are generated. Null IDs are left to separate
 * payload validation and are not treated as collisions here.
 */
public final class DuplicateId {

    private DuplicateId() {}

    /**
     * The first duplicate ID found anywhere in the problem, described for a 400
     * response, or empty when every ID is distinct.
     */
    public static Optional<String> firstDuplicate(List<Employee> employees, List<Position> positions) {
        Set<String> seen = new HashSet<>();
        if (employees != null) {
            for (Employee e : employees) {
                if (isDuplicate(seen, e.getId())) return Optional.of(message(e.getId()));
                if (e.getBlocks() != null) {
                    for (Block b : e.getBlocks()) {
                        if (isDuplicate(seen, b.getId())) return Optional.of(message(b.getId()));
                    }
                }
                if (e.getRules() != null) {
                    for (Rule r : e.getRules()) {
                        if (isDuplicate(seen, r.getId())) return Optional.of(message(r.getId()));
                        if (r.getChanges() != null) {
                            for (Change c : r.getChanges()) {
                                if (isDuplicate(seen, c.getId())) return Optional.of(message(c.getId()));
                            }
                        }
                    }
                }
            }
        }
        if (positions != null) {
            for (Position p : positions) {
                if (isDuplicate(seen, p.getId())) return Optional.of(message(p.getId()));
                if (p.getShifts() != null) {
                    for (ShiftTemplate s : p.getShifts()) {
                        if (isDuplicate(seen, s.getId())) return Optional.of(message(s.getId()));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** True if {@code id} was already seen; otherwise records it and returns false. Nulls are skipped. */
    private static boolean isDuplicate(Set<String> seen, String id) {
        return id != null && !seen.add(id);
    }

    private static String message(String id) {
        return "Duplicate entity id '" + id + "' in the submitted problem.";
    }
}
