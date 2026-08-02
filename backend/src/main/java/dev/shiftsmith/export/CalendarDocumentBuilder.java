package dev.shiftsmith.export;

import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.Position;
import dev.shiftsmith.domain.ShiftTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@link ExportDocument} for a calendar export: takes the problem plus the
 * assignments in range and lays out exactly what the read-only Plan calendars draw —
 * one section per requested person/position.
 *
 * <p>This is the server-side counterpart of the Personnel/Positions views
 * ({@code frontend/src/planview.jsx} {@code buildPersonEvents} / {@code buildPositionEvents}
 * and {@code calendar.jsx} {@code calendarDays} / {@code packLanes}). The two must agree:
 * a change to how the calendar expands a range, packs overlaps or labels a column
 * belongs in both places, and {@code CalendarDocumentBuilderTest} pins the shared rules.
 *
 * <p>Pure and stateless — no CDI, no database — so it is unit-testable on plain fixtures.
 */
public final class CalendarDocumentBuilder {

    /** Chips a month cell can show before it starts saying "+n more". */
    private static final int MONTH_CHIP_LIMIT = 4;

    /** Dropped-shift footnotes are a warning, not a report; keep the list short. */
    private static final int MAX_DROPPED_ITEMS = 8;

    private final ExportRequest req;
    private final ExportLabels labels;
    private final Map<String, Employee> employeesById;
    private final List<Position> positions;
    /** {@code templateId@date → ordered employee ids}, the same shape as the UI's assign map. */
    private final Map<String, List<String>> assign;
    private final LocalDateTime now;
    /** The visible range, and the lead-in day an overnight shift can spill from. */
    private final List<LocalDate> dayList;
    private final List<LocalDate> buildDays;

    public CalendarDocumentBuilder(ExportRequest req, List<Employee> employees, List<Position> positions,
                                   Map<String, List<String>> assign, LocalDateTime now) {
        this.req = req;
        this.labels = ExportLabels.of(req.lang());
        this.employeesById = new LinkedHashMap<>();
        for (Employee e : employees) this.employeesById.put(e.getId(), e);
        this.positions = positions;
        this.assign = assign;
        this.now = now;
        this.dayList = calendarDays(req.view(), req.anchor());
        List<LocalDate> lead = new ArrayList<>(dayList.size() + 1);
        lead.add(dayList.get(0).minusDays(1));
        lead.addAll(dayList);
        this.buildDays = List.copyOf(lead);
    }

    /** The days a view+anchor renders — the port of {@code calendarDays}. */
    public static List<LocalDate> calendarDays(String view, LocalDate anchor) {
        if ("day".equals(view)) return List.of(anchor);
        if ("week".equals(view)) {
            LocalDate start = startOfWeek(anchor);
            return daysFrom(start, 7);
        }
        // Month: whole weeks, but only those that actually touch the month — no
        // trailing week lying entirely in the next one.
        LocalDate first = anchor.withDayOfMonth(1);
        LocalDate last = anchor.with(TemporalAdjusters.lastDayOfMonth());
        LocalDate start = startOfWeek(first);
        int weeks = (int) java.time.temporal.ChronoUnit.WEEKS.between(start, startOfWeek(last)) + 1;
        return daysFrom(start, weeks * 7);
    }

    private static LocalDate startOfWeek(LocalDate d) {
        return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static List<LocalDate> daysFrom(LocalDate start, int count) {
        List<LocalDate> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) out.add(start.plusDays(i));
        return out;
    }

    /**
     * The dates whose assignments the document needs: the visible range plus the day
     * before it, because an overnight shift anchored on that lead-in day spills its
     * tail into the first visible column.
     */
    public static LocalDate loadFrom(String view, LocalDate anchor) {
        return calendarDays(view, anchor).get(0).minusDays(1);
    }

    /** Exclusive end of the range whose assignments the document needs. */
    public static LocalDate loadTo(String view, LocalDate anchor) {
        List<LocalDate> days = calendarDays(view, anchor);
        return days.get(days.size() - 1).plusDays(1);
    }

    public ExportDocument build() {
        List<ExportDocument.Section> sections = new ArrayList<>();
        for (ExportRequest.Scope scope : req.scopes()) {
            ExportDocument.Section section = switch (scope.kind()) {
                case PERSON -> personSection(scope.id());
                case POSITION -> positionSection(scope.id());
            };
            if (section != null) sections.add(section);
        }
        if (sections.isEmpty()) throw new IllegalArgumentException("no such person or position");
        return new ExportDocument(
                new ExportDocument.Meta("ShiftSmith", labels.generated(now),
                        req.paper(), req.orientation(), req.view()),
                sections,
                new ExportDocument.Labels(labels.empty()));
    }

    // --- sections ------------------------------------------------------------

    /** Every shift, across all positions, that {@code employee} is assigned to. */
    private ExportDocument.Section personSection(String employeeId) {
        Employee emp = employeesById.get(employeeId);
        if (emp == null) return null;
        List<Event> events = new ArrayList<>();
        for (LocalDate day : buildDays) {
            for (Position p : positions) {
                for (ShiftTemplate sh : p.getShifts()) {
                    if (!sh.occursOn(day)) continue;
                    if (!crewIds(sh.getId(), day).contains(employeeId)) continue;
                    events.add(new Event(day, sh.getStart(), sh.getEnd(), Palette.colorAt(p.getColor()),
                            p.getName(), List.of(), 0));
                }
            }
        }
        double hours = 0;
        for (Event e : events) if (inVisibleRange(e)) hours += e.durationHours();
        long count = events.stream().filter(this::inVisibleRange).count();
        return section(fullName(emp), subtitleFor(emp), events, List.of(
                new ExportDocument.Stat(labels.assignedShifts(), String.valueOf(count)),
                new ExportDocument.Stat(labels.assignedHours(), labels.hours(hours))));
    }

    /** One event per shift occurrence of {@code position}, listing its crew. */
    private ExportDocument.Section positionSection(String positionId) {
        Position pos = positions.stream().filter(p -> positionId.equals(p.getId())).findFirst().orElse(null);
        if (pos == null) return null;
        // Every shift of a position takes the position's own colour: one page, one
        // colour family. Shifts are told apart by their time, which is how the app
        // identifies them too (there is no shift name).
        ExportDocument.Color colour = Palette.colorAt(pos.getColor());
        List<Event> events = new ArrayList<>();
        int filled = 0;
        int open = 0;
        for (ShiftTemplate sh : pos.getShifts()) {
            for (LocalDate day : buildDays) {
                if (!sh.occursOn(day)) continue;
                // Each assignee keeps their own colour, so the avatar on paper is the
                // avatar on screen even though the chip is the position's colour.
                List<ExportDocument.Crew> crew = new ArrayList<>();
                for (String id : crewIds(sh.getId(), day)) {
                    Employee e = employeesById.get(id);
                    if (e != null) {
                        crew.add(new ExportDocument.Crew(fullName(e), initials(e), Palette.colorAt(e.getColor())));
                    }
                }
                int headcount = Math.max(sh.getHeadcount(), crew.size());
                int openHere = Math.max(0, headcount - crew.size());
                Event ev = new Event(day, sh.getStart(), sh.getEnd(), colour, "", List.copyOf(crew), openHere);
                events.add(ev);
                if (inVisibleRange(ev)) {
                    filled += crew.size();
                    open += openHere;
                }
            }
        }
        // No subtitle: the position's name is the whole story, and a shift-type count
        // says nothing a reader of the page needs.
        return section(pos.getName(), "", events, List.of(
                new ExportDocument.Stat(labels.filledSlots(), String.valueOf(filled)),
                new ExportDocument.Stat(labels.openSlotsLabel(), String.valueOf(open))));
    }

    private ExportDocument.Section section(String title, String subtitle, List<Event> events,
                                           List<ExportDocument.Stat> stats) {
        Map<LocalDate, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < dayList.size(); i++) index.put(dayList.get(i), i);

        // Explode events into day-local pieces clipped to the printed band, then pack
        // each day's pieces into side-by-side lanes.
        Map<LocalDate, List<Piece>> byDay = new LinkedHashMap<>();
        for (LocalDate d : dayList) byDay.put(d, new ArrayList<>());
        List<Event> dropped = new ArrayList<>();
        for (Event ev : events) {
            List<Piece> pieces = split(ev, req.dayStart(), req.dayEnd());
            boolean landed = false;
            for (Piece p : pieces) {
                List<Piece> bucket = byDay.get(p.date);
                if (bucket == null) continue; // lead-in day, or a tail past the end of the range
                bucket.add(p);
                landed = true;
            }
            // Only a shift that would have been visible counts as dropped — one that
            // merely sits on the lead-in day was never this page's business.
            if (!landed && inVisibleRange(ev)) dropped.add(ev);
        }

        List<ExportDocument.Segment> segments = new ArrayList<>();
        for (Map.Entry<LocalDate, List<Piece>> e : byDay.entrySet()) {
            for (Piece p : packLanes(e.getValue())) {
                Event ev = p.event;
                segments.add(new ExportDocument.Segment(
                        index.get(e.getKey()), p.start, p.end, p.lane, p.lanes, ev.colour,
                        timeRange(ev), ev.title, ev.crew,
                        ev.open > 0 ? labels.openSlots(ev.open) : null, ev.open > 0));
            }
        }

        boolean month = "month".equals(req.view());
        List<ExportDocument.Day> days = new ArrayList<>();
        for (LocalDate d : dayList) {
            List<ExportDocument.Chip> chips = month ? monthChips(byDay.get(d)) : List.of();
            int more = Math.max(0, chips.size() - MONTH_CHIP_LIMIT);
            days.add(new ExportDocument.Day(
                    d.toString(), labels.weekdayShort(d), subLabel(d), String.valueOf(d.getDayOfMonth()),
                    month ? !inFocusMonth(d, dayList) : isWeekend(d),
                    chips.subList(0, Math.min(chips.size(), MONTH_CHIP_LIMIT)),
                    more, more > 0 ? labels.more(more) : ""));
        }

        return new ExportDocument.Section(title, subtitle, rangeLabel(),
                new ExportDocument.Grid(req.dayStart(), req.dayEnd(), hourLabels()),
                weekdayHeads(), days, segments, legend(events), stats, dropped(dropped));
    }

    // --- layout --------------------------------------------------------------

    /** An event before layout: a shift occurrence on one anchor day. */
    private record Event(LocalDate date, int start, int end, ExportDocument.Color colour,
                         String title, List<ExportDocument.Crew> crew, int open) {
        /** The crew as plain text, for the places too small for avatars. */
        String crewNames() {
            return crew.stream().map(ExportDocument.Crew::name).collect(java.util.stream.Collectors.joining(", "));
        }

        /** Overnight when the clock wraps — the shift ends on the following day. */
        boolean overnight() { return end <= start; }

        double durationHours() {
            int e = overnight() ? end + 1440 : end;
            return (e - start) / 60.0;
        }
    }

    /** A day-local slice of an event, clipped to the printed band and lane-packed. */
    private static final class Piece {
        final Event event;
        final LocalDate date;
        final int start;
        final int end;
        int lane;
        int lanes = 1;

        Piece(Event event, LocalDate date, int start, int end) {
            this.event = event;
            this.date = date;
            this.start = start;
            this.end = end;
        }
    }

    /**
     * Split an event into per-day pieces clipped to {@code [from, to)}. An overnight
     * event contributes a head on its own day and a tail on the next; pieces outside
     * the band are dropped, pieces straddling a boundary are trimmed.
     */
    private static List<Piece> split(Event ev, int from, int to) {
        List<Piece> raw = new ArrayList<>(2);
        if (ev.overnight()) {
            raw.add(new Piece(ev, ev.date, ev.start, 1440));
            raw.add(new Piece(ev, ev.date.plusDays(1), 0, ev.end));
        } else {
            raw.add(new Piece(ev, ev.date, ev.start, ev.end));
        }
        List<Piece> out = new ArrayList<>(2);
        for (Piece p : raw) {
            int start = Math.max(p.start, from);
            int end = Math.min(p.end, to);
            if (end - start < 1) continue;
            out.add(new Piece(ev, p.date, start, end));
        }
        return out;
    }

    /**
     * Greedy side-by-side packing for pieces that share a day — the port of
     * {@code calendar.jsx} {@code packLanes}, so a printed overlap sits where the
     * on-screen one does.
     */
    static List<Piece> packLanes(List<Piece> pieces) {
        List<Piece> sorted = new ArrayList<>(pieces);
        sorted.sort(Comparator.<Piece>comparingInt(p -> p.start).thenComparing(p -> -p.end));
        List<Integer> laneEnds = new ArrayList<>();
        for (Piece p : sorted) {
            boolean placed = false;
            for (int i = 0; i < laneEnds.size(); i++) {
                if (laneEnds.get(i) <= p.start) {
                    p.lane = i;
                    laneEnds.set(i, p.end);
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                p.lane = laneEnds.size();
                laneEnds.add(p.end);
            }
        }
        for (Piece p : sorted) {
            int max = 1;
            for (Piece o : sorted) {
                if (o == p) continue;
                if (o.start < p.end && o.end > p.start) max = Math.max(max, Math.max(o.lane, p.lane) + 1);
            }
            p.lanes = Math.max(max, p.lane + 1);
        }
        return sorted;
    }

    private List<ExportDocument.Chip> monthChips(List<Piece> pieces) {
        List<Piece> sorted = new ArrayList<>(pieces);
        sorted.sort(Comparator.comparingInt(p -> p.start));
        List<ExportDocument.Chip> out = new ArrayList<>(sorted.size());
        for (Piece p : sorted) {
            Event ev = p.event;
            // Same content as a day/week chip, one line deep: a person's page names the
            // position, a position's page draws its crew as avatars, and either way an
            // unfilled shift says so — a month cell must not be the one view that hides it.
            out.add(new ExportDocument.Chip(minLabel(ev.start), ev.title, ev.crew,
                    ev.open > 0 ? labels.openSlots(ev.open) : null, ev.colour, ev.open > 0));
        }
        return out;
    }

    // --- labels --------------------------------------------------------------

    /**
     * Wall-clock label; the app's calendars are 24h throughout, so the PDF is too.
     * The port of {@code SS.minLabel}: minute 1440 reads "24:00" — the end of this
     * day, not the start of the next — which is how the editor spells it too.
     */
    static String minLabel(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    /** The event's real hours, not the clipped piece's — an overnight tail still reads "22:00–06:00". */
    private static String timeRange(Event ev) {
        return minLabel(ev.start) + "–" + minLabel(ev.end);
    }

    private String rangeLabel() {
        if (dayList.isEmpty()) return "";
        return switch (req.view()) {
            case "day" -> labels.fullDate(dayList.get(0));
            case "month" -> labels.monthYear(dayList.get(dayList.size() / 2));
            default -> labels.weekRange(dayList.get(0), dayList.get(dayList.size() - 1));
        };
    }

    /**
     * A day view's header already carries the full date; a week column repeats it; a
     * month cell shows its own number, so only the 1st needs to name its month.
     */
    private String subLabel(LocalDate d) {
        return switch (req.view()) {
            case "day" -> "";
            case "month" -> d.getDayOfMonth() == 1 ? labels.monthShort(d) : "";
            default -> labels.dayMonth(d);
        };
    }

    private Map<String, String> hourLabels() {
        Map<String, String> out = new LinkedHashMap<>();
        for (int h = ceilDiv(req.dayStart(), 60); h <= req.dayEnd() / 60; h++) {
            out.put(String.valueOf(h), minLabel(h * 60));
        }
        return out;
    }

    private List<String> weekdayHeads() {
        List<String> out = new ArrayList<>(7);
        LocalDate monday = LocalDate.of(2024, 1, 1); // any Monday
        for (int i = 0; i < 7; i++) out.add(labels.weekdayShort(monday.plusDays(i)));
        return out;
    }

    /** Distinct labelled colours on the page — only worth printing when there are several. */
    private List<ExportDocument.LegendEntry> legend(List<Event> events) {
        Map<String, ExportDocument.LegendEntry> seen = new LinkedHashMap<>();
        for (Event ev : events) {
            if (ev.title.isEmpty()) continue;
            seen.putIfAbsent(ev.title, new ExportDocument.LegendEntry(ev.title, ev.colour));
        }
        return seen.size() > 1 ? List.copyOf(seen.values()) : List.of();
    }

    private ExportDocument.Dropped dropped(List<Event> dropped) {
        if (dropped.isEmpty()) return new ExportDocument.Dropped(0, "", List.of());
        List<Event> sorted = new ArrayList<>(dropped);
        sorted.sort(Comparator.<Event, LocalDate>comparing(e -> e.date).thenComparingInt(e -> e.start));
        List<String> items = new ArrayList<>();
        for (Event ev : sorted.subList(0, Math.min(sorted.size(), MAX_DROPPED_ITEMS))) {
            String who = !ev.title.isEmpty() ? ev.title : ev.crewNames();
            items.add((labels.weekdayShort(ev.date) + " " + labels.dayMonth(ev.date)
                    + " · " + timeRange(ev) + (who.isEmpty() ? "" : " · " + who)).trim());
        }
        if (sorted.size() > items.size()) items.add(labels.more(sorted.size() - items.size()));
        return new ExportDocument.Dropped(sorted.size(),
                labels.droppedLabel(sorted.size(), minLabel(req.dayStart()), minLabel(req.dayEnd())),
                items);
    }

    // --- helpers -------------------------------------------------------------

    /** Stats and drop-out warnings count the visible range only, not the lead-in day. */
    private boolean inVisibleRange(Event ev) {
        return !ev.date.isBefore(dayList.get(0));
    }

    private List<String> crewIds(String templateId, LocalDate date) {
        return assign.getOrDefault(templateId + "@" + date, List.of());
    }

    private String fullName(Employee e) {
        String first = e.getFirstName() == null ? "" : e.getFirstName();
        String last = e.getLastName() == null ? "" : e.getLastName();
        String joined = "last".equals(req.nameOrder())
                ? (last + " " + first) : (first + " " + last);
        return joined.trim();
    }

    private String subtitleFor(Employee e) {
        return e.getSkills() == null || e.getSkills().isEmpty()
                ? labels.personnel() : String.join(" · ", e.getSkills());
    }

    /**
     * Avatar initials — the port of {@code SS.empInitials}: first letter of each name,
     * in that order whatever the display order, so the badge is stable.
     */
    private static String initials(Employee e) {
        String s = firstLetter(e.getFirstName()) + firstLetter(e.getLastName());
        return s.isEmpty() ? "?" : s.toUpperCase(java.util.Locale.ROOT);
    }

    private static String firstLetter(String name) {
        String s = name == null ? "" : name.trim();
        return s.isEmpty() ? "" : s.substring(0, 1);
    }

    /** The month a month page is "about": the one owning most of its cells. */
    private static boolean inFocusMonth(LocalDate date, List<LocalDate> dayList) {
        Map<java.time.YearMonth, Integer> counts = new LinkedHashMap<>();
        for (LocalDate d : dayList) counts.merge(java.time.YearMonth.from(d), 1, Integer::sum);
        java.time.YearMonth focus = counts.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        return java.time.YearMonth.from(date).equals(focus);
    }

    private static boolean isWeekend(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
