package dev.shiftsmith.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Server-side guard against calendar entries that occupy the same minute.
 *
 * <p>Mirrors the frontend's {@code entriesOverlap} check so a hand-crafted or
 * buggy {@code PUT /api/problem} can't slip overlapping entries past the UI:
 * within one employee no two availability blocks may overlap, and within one
 * position no two shift templates may overlap.
 *
 * <p>Vacations and any other all-day entry are exempt — they span the whole day
 * on purpose and are a deliberate special case, so they never count as a clash.
 * Adjacent entries that merely touch at the seam (one ends exactly when the next
 * begins) are allowed, matching how availability windows merge when solving.
 *
 * <p>Recurrence repeats with a period of at most a week, so deciding any
 * none/daily/weekly combination only needs a nine-day window from the later
 * anchor — one day of slack on each side covers overnight spillover.
 */
public final class CalendarOverlap {

    private CalendarOverlap() {}

    /** A timed occupancy that can recur — the shape both Block and ShiftTemplate share. */
    private interface Entry {
        LocalDate anchor();
        int start();
        int end();
        boolean exempt();
        boolean occursOn(LocalDate d);
    }

    /**
     * The first overlapping pair anywhere in the problem, described for a 400
     * response, or empty when every calendar is clean.
     */
    public static Optional<String> firstConflict(List<Employee> employees, List<Position> positions) {
        if (employees != null) {
            for (Employee e : employees) {
                LocalDate day = firstOverlapDay(blockEntries(e.getBlocks()));
                if (day != null) {
                    return Optional.of("Employee '" + label(e.displayName(), e.getId())
                            + "' has overlapping availability entries on " + day + ".");
                }
            }
        }
        if (positions != null) {
            for (Position p : positions) {
                LocalDate day = firstOverlapDay(shiftEntries(p.getShifts()));
                if (day != null) {
                    return Optional.of("Position '" + label(p.getName(), p.getId())
                            + "' has overlapping shifts on " + day + ".");
                }
            }
        }
        return Optional.empty();
    }

    private static String label(String name, String id) {
        return name != null && !name.isBlank() ? name : id;
    }

    private static LocalDate firstOverlapDay(List<Entry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            for (int j = i + 1; j < entries.size(); j++) {
                LocalDate day = overlapDay(entries.get(i), entries.get(j));
                if (day != null) return day;
            }
        }
        return null;
    }

    /** The first calendar day on which a and b share a minute, or null if they never do. */
    private static LocalDate overlapDay(Entry a, Entry b) {
        if (a.exempt() || b.exempt() || a.anchor() == null || b.anchor() == null) return null;
        LocalDate later = a.anchor().isAfter(b.anchor()) ? a.anchor() : b.anchor();
        LocalDate windowStart = later.minusDays(1);
        // Collect every occupied span across the window first, then compare them
        // all — an overnight entry's span starts on one day but runs into the next.
        List<long[]> ia = intervals(a, windowStart);
        List<long[]> ib = intervals(b, windowStart);
        for (long[] x : ia) {
            for (long[] y : ib) {
                if (x[0] < y[1] && y[0] < x[1]) {
                    return LocalDate.ofEpochDay(Math.max(x[0], y[0]) / 1440L); // day the clash begins
                }
            }
        }
        return null;
    }

    /** Absolute-minute spans {@code o} occupies on each day it occurs in the nine-day window. */
    private static List<long[]> intervals(Entry o, LocalDate windowStart) {
        List<long[]> out = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            LocalDate d = windowStart.plusDays(i);
            if (!o.occursOn(d)) continue;
            long base = d.toEpochDay() * 1440L;
            long end = o.end() > o.start() ? o.end() : o.end() + 1440L; // wrap overnight
            out.add(new long[]{base + o.start(), base + end});
        }
        return out;
    }

    // --- adapters -------------------------------------------------------

    private static List<Entry> blockEntries(List<Block> blocks) {
        List<Entry> out = new ArrayList<>();
        if (blocks == null) return out;
        for (Block b : blocks) {
            out.add(new Entry() {
                public LocalDate anchor() { return b.getDate(); }
                public int start() { return b.getStart(); }
                public int end() { return b.getEnd(); }
                public boolean exempt() { return b.isAllDay() || "vac".equals(b.getType()); }
                public boolean occursOn(LocalDate d) { return b.occursOn(d); }
            });
        }
        return out;
    }

    private static List<Entry> shiftEntries(List<ShiftTemplate> shifts) {
        List<Entry> out = new ArrayList<>();
        if (shifts == null) return out;
        for (ShiftTemplate s : shifts) {
            out.add(new Entry() {
                public LocalDate anchor() { return s.getDate(); }
                public int start() { return s.getStart(); }
                public int end() { return s.getEnd(); }
                public boolean exempt() { return false; } // shifts are always timed
                public boolean occursOn(LocalDate d) { return s.occursOn(d); }
            });
        }
        return out;
    }
}
