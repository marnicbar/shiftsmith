package dev.shiftsmith.realtime;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A typed "something changed" event pushed over SSE (issue #47, Phase 5), replacing
 * the full-snapshot frames. Clients refetch only the affected slice:
 *
 * <ul>
 *   <li>{@code employee}/{@code position}/{@code settings} (with {@code id}/{@code rev})
 *       — a problem edit; refetch that one resource (a {@code rev} matching what the
 *       client already holds means it was the client's own edit, so it can skip).</li>
 *   <li>{@code assignment} (with {@code from}/{@code to}) — pinned slots changed in a
 *       date range.</li>
 *   <li>{@code solver} — the solver advanced or its status changed; refetch the live
 *       schedule.</li>
 *   <li>{@code reload} — a coarse "refetch everything" (the deprecated bulk edit).</li>
 *   <li>{@code connected}/{@code heartbeat} — connection liveness only.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChangeEvent(String type, String id, Long rev, String from, String to) {

    public static ChangeEvent solver() { return new ChangeEvent("solver", null, null, null, null); }
    public static ChangeEvent reload() { return new ChangeEvent("reload", null, null, null, null); }
    public static ChangeEvent connected() { return new ChangeEvent("connected", null, null, null, null); }
    public static ChangeEvent heartbeat() { return new ChangeEvent("heartbeat", null, null, null, null); }

    /** A problem-edit event for one resource; {@code rev} is its new version (null = removed). */
    public static ChangeEvent entity(String type, String id, Long rev) {
        return new ChangeEvent(type, id, rev, null, null);
    }

    /** Pinned assignments changed within {@code [from, to]} (ISO dates). */
    public static ChangeEvent assignment(String from, String to) {
        return new ChangeEvent("assignment", null, null, from, to);
    }
}
