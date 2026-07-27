package dev.shiftsmith.export;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A validated calendar-export request: which calendars, over which view, printed how.
 *
 * <p>{@code scopes} is a list so one PDF can hold several sections — the export is
 * built for a batch of one today, and the same path serves "everyone, one page each"
 * when a batch UI lands.
 */
public record ExportRequest(List<Scope> scopes, String view, LocalDate anchor,
                            int dayStart, int dayEnd,
                            String paper, String orientation, String lang, String nameOrder) {

    /** One section of the document: a person's calendar or a position's. */
    public record Scope(Kind kind, String id) {
        public enum Kind { PERSON, POSITION }

        /** Parse a {@code person:<id>} / {@code position:<id>} token, as the read API spells it. */
        public static Scope parse(String token) {
            if (token == null) throw new IllegalArgumentException("scope is required");
            int sep = token.indexOf(':');
            if (sep <= 0 || sep == token.length() - 1) {
                throw new IllegalArgumentException(
                        "scope must be 'person:<id>' or 'position:<id>', got '" + token + "'");
            }
            String kind = token.substring(0, sep);
            String id = token.substring(sep + 1);
            return switch (kind) {
                case "person" -> new Scope(Kind.PERSON, id);
                case "position" -> new Scope(Kind.POSITION, id);
                default -> throw new IllegalArgumentException("unknown scope kind '" + kind + "'");
            };
        }
    }

    public static final List<String> VIEWS = List.of("day", "week", "month");
    public static final List<String> PAPERS = List.of("a4", "a3", "us-letter");
    public static final List<String> ORIENTATIONS = List.of("landscape", "portrait");

    /** A batch has to stay bounded: one process, one timeout, one response. */
    public static final int MAX_SCOPES = 60;

    /** Shortest printable band. Below an hour the grid has nothing to scale to. */
    public static final int MIN_BAND_MINUTES = 60;

    /**
     * Normalise and validate raw query parameters.
     *
     * @throws IllegalArgumentException with a message safe to return to the client
     */
    public static ExportRequest of(List<String> scopeTokens, String view, LocalDate anchor,
                                   Integer dayStart, Integer dayEnd,
                                   String paper, String orientation, String lang, String nameOrder) {
        if (scopeTokens == null || scopeTokens.isEmpty()) {
            throw new IllegalArgumentException("at least one scope is required");
        }
        if (scopeTokens.size() > MAX_SCOPES) {
            throw new IllegalArgumentException("too many scopes (max " + MAX_SCOPES + ")");
        }
        List<Scope> scopes = new ArrayList<>();
        for (String token : scopeTokens) scopes.add(Scope.parse(token));

        String v = pick(view, VIEWS, "week");
        LocalDate at = anchor == null ? LocalDate.now() : anchor;

        // A month page has no time axis, so it always covers whole days.
        int from = "month".equals(v) ? 0 : clamp(dayStart == null ? 6 * 60 : dayStart, 0, 1440 - MIN_BAND_MINUTES);
        int to = "month".equals(v) ? 1440 : clamp(dayEnd == null ? 22 * 60 : dayEnd, from + MIN_BAND_MINUTES, 1440);

        return new ExportRequest(List.copyOf(scopes), v, at, from, to,
                pick(paper, PAPERS, "a4"), pick(orientation, ORIENTATIONS, defaultOrientation(v)),
                lang == null ? "en" : lang,
                "last".equals(nameOrder) ? "last" : "first");
    }

    /**
     * A week or month page needs the width for its seven columns; a single day has one,
     * which on a landscape sheet is absurdly wide. So day pages stand up by default.
     */
    public static String defaultOrientation(String view) {
        return "day".equals(view) ? "portrait" : "landscape";
    }

    private static String pick(String value, List<String> allowed, String fallback) {
        if (value == null) return fallback;
        String v = value.toLowerCase(Locale.ROOT);
        return allowed.contains(v) ? v : fallback;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(v, hi));
    }
}
