package dev.shiftsmith.export;

import java.util.List;
import java.util.Map;

/**
 * The render model consumed by {@code typst/calendar.typ}.
 *
 * <p>The template composes no text of its own: every label, date, time and name here
 * is already formatted and localised by {@link CalendarDocumentBuilder}. Field names
 * are part of the contract with the template — renaming one means editing the
 * {@code .typ} too.
 *
 * <p>A document holds a list of {@link Section}s, each starting a new page. A single
 * export is just a one-section document, so batch export ("one page per person") is
 * the same code path with a longer list.
 */
public record ExportDocument(Meta meta, List<Section> sections, Labels labels) {

    /** Document-wide settings: everything that is true of the whole PDF. */
    public record Meta(String brand, String generated, String paper, String orientation, String view) {}

    /** One page (or page run) of the PDF: one person's or one position's calendar. */
    public record Section(String title, String subtitle, String range, Grid grid,
                          List<String> weekdayHeads, List<Day> days, List<Segment> segments,
                          List<LegendEntry> legend, List<Stat> stats, Dropped dropped) {}

    /** The printed time band, plus a pre-formatted label for every hour line drawn. */
    public record Grid(int dayStart, int dayEnd, Map<String, String> hourLabels) {}

    /**
     * One column (day/week view) or cell (month view). {@code chips} and {@code more}
     * are only populated for the month view, which has no time axis to place events on.
     */
    public record Day(String date, String head, String sub, String num, boolean dim,
                      List<Chip> chips, int more, String moreLabel) {}

    /**
     * A one-line summary of a shift inside a month cell. It carries the same three
     * things the on-screen month cell shows — the time, who is on ({@code crew}, drawn
     * as avatars, or {@code label} where there is no crew to name) and the
     * {@code note} counting the slots still open — just packed onto one clipped line.
     */
    public record Chip(String time, String label, List<Crew> crew, String note, Color color, boolean open) {}

    /**
     * A shift as it is drawn on the time grid: already split at midnight, clipped to
     * the printed band, and assigned a {@code lane} of {@code lanes} side-by-side
     * columns so overlapping shifts sit beside each other. {@code day} indexes
     * {@link Section#days}.
     */
    public record Segment(int day, int start, int end, int lane, int lanes, Color color,
                          String time, String title, List<Crew> crew, String note, boolean open) {}

    /**
     * One assignee on a shift, drawn as the initials-in-a-coloured-circle avatar the
     * Positions view uses on screen. The colour is the <em>person's</em> swatch, not the
     * position's, so the same face reads the same everywhere.
     */
    public record Crew(String name, String initials, Color color) {}

    /** An OKLCH swatch, in components rather than a CSS string — see {@link Palette}. */
    public record Color(double l, double c, double h) {}

    public record LegendEntry(String label, Color color) {}

    /** One "in this view" figure, printed in the footer band. */
    public record Stat(String k, String v) {}

    /**
     * The shifts that fell outside the printed hours and so are missing from the page.
     * Printed as a footnote rather than dropped silently, and surfaced in the export
     * dialog before the user commits to a download.
     */
    public record Dropped(int count, String label, List<String> items) {}

    /** The few strings the template needs for states it decides on itself. */
    public record Labels(String empty) {}
}
