package dev.shiftsmith.export;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Every user-visible string and date format the PDF needs, per language.
 *
 * <p>The UI's own strings live in {@code frontend/src/i18n/locales/*.json}; these are
 * the export's counterpart, kept here because the document is built server-side. When
 * you add a language to {@code i18n/index.js} {@code LANGUAGES}, add it here too —
 * unknown tags fall back to English, so a miss degrades rather than breaks.
 *
 * <p>Date patterns are spelled out rather than taken from {@code FormatStyle}, because
 * CLDR's medium/short forms don't match what the on-screen calendar renders through
 * {@code Intl.DateTimeFormat}, and paper is supposed to look like the screen.
 */
public final class ExportLabels {

    private final Locale locale;
    private final boolean german;

    private ExportLabels(Locale locale, boolean german) {
        this.locale = locale;
        this.german = german;
    }

    public static ExportLabels of(String lang) {
        boolean de = lang != null && lang.toLowerCase(Locale.ROOT).startsWith("de");
        return new ExportLabels(de ? Locale.GERMANY : Locale.US, de);
    }

    public Locale locale() { return locale; }

    // --- dates ---------------------------------------------------------------

    /** Short weekday, as the calendar's column heads show it ("Mon" / "Mo"). */
    public String weekdayShort(LocalDate d) {
        return stripDot(d.getDayOfWeek().getDisplayName(TextStyle.SHORT, locale));
    }

    /** Day + short month, as a week column's sub-label ("Jul 27" / "27. Jul"). */
    public String dayMonth(LocalDate d) {
        return fmt(german ? "d. MMM" : "MMM d").format(d);
    }

    /** Short month alone, marking where a new month starts in a month grid. */
    public String monthShort(LocalDate d) {
        return stripDot(d.getMonth().getDisplayName(TextStyle.SHORT, locale));
    }

    /** The header range of a day page ("Monday, July 27, 2026"). */
    public String fullDate(LocalDate d) {
        return fmt(german ? "EEEE, d. MMMM yyyy" : "EEEE, MMMM d, yyyy").format(d);
    }

    /** The header range of a week page ("Jul 27 – Aug 2, 2026"). */
    public String weekRange(LocalDate from, LocalDate to) {
        return fmt(german ? "d. MMM" : "MMM d").format(from) + " – "
                + fmt(german ? "d. MMM yyyy" : "MMM d, yyyy").format(to);
    }

    /** The header range of a month page ("July 2026"). */
    public String monthYear(LocalDate d) {
        return fmt("MMMM yyyy").format(d);
    }

    public String dateTime(LocalDateTime at) {
        return fmt(german ? "d. MMM yyyy, HH:mm" : "MMM d, yyyy, HH:mm").format(at);
    }

    // --- strings -------------------------------------------------------------

    public String generated(LocalDateTime at) {
        return (german ? "Erstellt " : "Generated ") + dateTime(at);
    }

    public String empty() {
        return german ? "Keine Schichten in diesem Bereich" : "No shifts in this range";
    }

    public String openSlots(int n) {
        return german ? n + " offen" : n + " open";
    }

    public String more(int n) {
        return german ? "+" + n + " weitere" : "+" + n + " more";
    }

    public String personnel() { return german ? "Personal" : "Personnel"; }

    public String assignedShifts() { return german ? "Zugewiesene Schichten" : "Assigned shifts"; }
    public String assignedHours() { return german ? "Zugewiesene Stunden" : "Assigned hours"; }
    public String filledSlots() { return german ? "Besetzte Plätze" : "Filled slots"; }
    public String openSlotsLabel() { return german ? "Offene Plätze" : "Open slots"; }

    public String hours(double h) {
        String n = h == Math.rint(h) ? String.valueOf((long) h) : String.valueOf(Math.round(h * 10) / 10.0);
        if (german) n = n.replace('.', ',');
        return n + " h";
    }

    /** The footnote naming how many shifts the printed hours left out. */
    public String droppedLabel(int count, String from, String to) {
        if (german) {
            return count == 1
                    ? "1 Schicht liegt ausserhalb von " + from + "–" + to + " und fehlt auf dieser Seite:"
                    : count + " Schichten liegen ausserhalb von " + from + "–" + to + " und fehlen auf dieser Seite:";
        }
        return count == 1
                ? "1 shift falls outside " + from + "–" + to + " and is not on this page:"
                : count + " shifts fall outside " + from + "–" + to + " and are not on this page:";
    }

    private DateTimeFormatter fmt(String pattern) {
        return DateTimeFormatter.ofPattern(pattern, locale);
    }

    /** Java's German abbreviations carry a trailing dot ("Mo."); the UI's don't. */
    private static String stripDot(String s) {
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
