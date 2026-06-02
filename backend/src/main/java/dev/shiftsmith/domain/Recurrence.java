package dev.shiftsmith.domain;

import java.time.LocalDate;

/**
 * Port of the frontend `matchesDay` / `occursOn` logic so the solver expands
 * recurring templates and blocks exactly the way the UI previews them.
 *
 * repeat:
 *   "none"   — only on {@code anchor}
 *   "daily"  — every day on/after {@code anchor}
 *   "weekly" — same weekday as {@code anchor}, on/after {@code anchor}; or, when
 *              {@code days} is given, every selected weekday on/after {@code anchor}
 *              (Mon=0 … Sun=6, matching the frontend), like a clock/alarm repeat.
 * Optional {@code until} (inclusive end) and {@code except} (skipped ISO dates)
 * mirror the frontend fields of the same name.
 */
public final class Recurrence {

    private Recurrence() {}

    public static boolean occursOn(LocalDate anchor, String repeat, LocalDate date,
                                   LocalDate until, java.util.Set<LocalDate> except) {
        return occursOn(anchor, repeat, date, until, except, null);
    }

    public static boolean occursOn(LocalDate anchor, String repeat, LocalDate date,
                                   LocalDate until, java.util.Set<LocalDate> except,
                                   java.util.Set<Integer> days) {
        if (anchor == null || date == null) return false;
        if (until != null && date.isAfter(until)) return false;
        if (except != null && except.contains(date)) return false;
        if (repeat == null || repeat.equals("none")) return date.equals(anchor);
        if (date.isBefore(anchor)) return false;
        if (repeat.equals("daily")) return true;
        if (repeat.equals("weekly")) {
            if (days != null && !days.isEmpty()) {
                int wd = (date.getDayOfWeek().getValue() + 6) % 7; // Mon=0 … Sun=6
                return days.contains(wd);
            }
            return date.getDayOfWeek() == anchor.getDayOfWeek();
        }
        return false;
    }
}
