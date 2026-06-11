package dev.shiftsmith.rest.dto;

import java.util.List;

/**
 * A page of a list resource (issue #47, Phase 3): the {@code items} for the
 * requested {@code page}/{@code size} plus the {@code total} count so the client
 * can render pagination without loading everything.
 */
public record Page<T>(List<T> items, int page, int size, int total) {

    /** Slice {@code all} for a 0-based {@code page} of {@code size} (both clamped to sane values). */
    public static <T> Page<T> of(List<T> all, int page, int size) {
        int safeSize = size <= 0 ? 50 : Math.min(size, 500);
        int safePage = Math.max(page, 0);
        int total = all.size();
        int from = Math.min(safePage * safeSize, total);
        int to = Math.min(from + safeSize, total);
        return new Page<>(List.copyOf(all.subList(from, to)), safePage, safeSize, total);
    }
}
