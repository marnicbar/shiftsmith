package dev.shiftsmith.export;

import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.Position;
import dev.shiftsmith.domain.ShiftTemplate;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The layout rules the PDF shares with the on-screen calendar: which days a view
 * covers, how an overnight shift is split, what the printed hours clip away, and how
 * overlapping shifts are packed side by side.
 */
class CalendarDocumentBuilderTest {

    /** A Monday, so week views start exactly here. */
    private static final LocalDate MON = LocalDate.of(2026, 7, 27);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 27, 14, 3);

    // --- fixtures ------------------------------------------------------------

    private static Employee emp(String id, String first, String last, int colour) {
        Employee e = new Employee();
        e.setId(id);
        e.setFirstName(first);
        e.setLastName(last);
        e.setColor(colour);
        e.setSkills(new java.util.LinkedHashSet<>());
        return e;
    }

    private static ShiftTemplate shift(String id, LocalDate date, int start, int end, int headcount) {
        ShiftTemplate t = new ShiftTemplate();
        t.setId(id);
        t.setDate(date);
        t.setStart(start);
        t.setEnd(end);
        t.setHeadcount(headcount);
        t.setRepeat("none");
        return t;
    }

    private static Position pos(String id, String name, int colour, ShiftTemplate... shifts) {
        Position p = new Position();
        p.setId(id);
        p.setName(name);
        p.setColor(colour);
        p.setSkills(Set.of());
        p.setShifts(new ArrayList<>(List.of(shifts)));
        return p;
    }

    private static Map<String, List<String>> assign(String... pairs) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], List.of(pairs[i + 1].split(",")));
        }
        return m;
    }

    private record World(List<Employee> employees, List<Position> positions, Map<String, List<String>> assign) {}

    /** Kitchen (2 slots, 08:00–16:00 Mon) with Anna and Ben on it. */
    private static World world() {
        return new World(
                List.of(emp("e1", "Anna", "Müller", 0), emp("e2", "Ben", "Ott", 1)),
                List.of(pos("p1", "Kitchen", 2, shift("s1", MON, 480, 960, 2))),
                assign("s1@2026-07-27", "e1,e2"));
    }

    private static ExportRequest req(String view, String... scopes) {
        return ExportRequest.of(List.of(scopes), view, MON, 6 * 60, 22 * 60,
                "a4", "landscape", "en", "first");
    }

    private static ExportDocument build(ExportRequest r, World w) {
        return new CalendarDocumentBuilder(r, w.employees(), w.positions(), w.assign(), NOW).build();
    }

    private static ExportDocument.Section only(ExportDocument doc) {
        assertThat(doc.sections()).hasSize(1);
        return doc.sections().get(0);
    }

    // --- day lists -----------------------------------------------------------

    @Test
    void aWeekViewCoversMondayToSunday() {
        List<LocalDate> days = CalendarDocumentBuilder.calendarDays("week", LocalDate.of(2026, 7, 30));
        assertThat(days).hasSize(7)
                .startsWith(LocalDate.of(2026, 7, 27))
                .endsWith(LocalDate.of(2026, 8, 2));
    }

    @Test
    void aMonthViewCoversWholeWeeksThatTouchTheMonth() {
        List<LocalDate> days = CalendarDocumentBuilder.calendarDays("month", LocalDate.of(2026, 7, 15));
        // July 2026 starts on a Wednesday and ends on a Friday: Jun 29 … Aug 2.
        assertThat(days).hasSize(35)
                .startsWith(LocalDate.of(2026, 6, 29))
                .endsWith(LocalDate.of(2026, 8, 2));
        assertThat(days.size() % 7).isZero();
    }

    @Test
    void theLoadRangeIncludesTheOvernightLeadInDay() {
        assertThat(CalendarDocumentBuilder.loadFrom("week", MON)).isEqualTo(MON.minusDays(1));
        assertThat(CalendarDocumentBuilder.loadTo("week", MON)).isEqualTo(MON.plusDays(7));
    }

    // --- placement -----------------------------------------------------------

    @Test
    void placesAShiftInTheRightColumnWithItsCrew() {
        ExportDocument.Section s = only(build(req("week", "position:p1"), world()));
        assertThat(s.title()).isEqualTo("Kitchen");
        assertThat(s.range()).isEqualTo("Jul 27 – Aug 2, 2026");
        assertThat(s.segments()).hasSize(1);
        ExportDocument.Segment seg = s.segments().get(0);
        assertThat(seg.day()).isZero();
        assertThat(seg.start()).isEqualTo(480);
        assertThat(seg.end()).isEqualTo(960);
        assertThat(seg.time()).isEqualTo("08:00–16:00");
        assertThat(seg.crew()).extracting(ExportDocument.Crew::name)
                .containsExactly("Anna Müller", "Ben Ott");
        assertThat(seg.note()).isNull();
        assertThat(seg.open()).isFalse();
    }

    @Test
    void givesEachAssigneeTheirOwnAvatar() {
        ExportDocument.Segment seg = only(build(req("week", "position:p1"), world())).segments().get(0);
        // The chip is the position's colour; the badges are the people's own, exactly
        // as the Positions view draws them on screen.
        assertThat(seg.color()).isEqualTo(Palette.colorAt(2));
        assertThat(seg.crew()).extracting(ExportDocument.Crew::initials).containsExactly("AM", "BO");
        assertThat(seg.crew()).extracting(ExportDocument.Crew::color)
                .containsExactly(Palette.colorAt(0), Palette.colorAt(1));
    }

    @Test
    void aPositionPageCarriesNoSubtitle() {
        assertThat(only(build(req("week", "position:p1"), world())).subtitle()).isEmpty();
    }

    @Test
    void countsAndLabelsUnfilledSlots() {
        World w = world();
        ExportDocument.Section s = only(build(req("week", "position:p1"),
                new World(w.employees(), w.positions(), assign("s1@2026-07-27", "e1"))));
        assertThat(s.segments().get(0).note()).isEqualTo("1 open");
        assertThat(s.segments().get(0).open()).isTrue();
        assertThat(s.stats()).extracting(ExportDocument.Stat::v).containsExactly("1", "1");
    }

    @Test
    void aPersonPageShowsTheirShiftsColouredByPosition() {
        World w = world();
        ExportDocument.Section s = only(build(req("week", "person:e1"), w));
        assertThat(s.title()).isEqualTo("Anna Müller");
        assertThat(s.segments()).hasSize(1);
        assertThat(s.segments().get(0).title()).isEqualTo("Kitchen");
        assertThat(s.segments().get(0).color()).isEqualTo(Palette.colorAt(2)); // the position's
        assertThat(s.stats()).extracting(ExportDocument.Stat::v).containsExactly("1", "8 h");
    }

    @Test
    void aPersonSeesNothingOfAShiftTheyAreNotOn() {
        World w = world();
        assertThat(only(build(req("week", "person:e2"),
                new World(w.employees(), w.positions(), assign("s1@2026-07-27", "e1")))).segments())
                .isEmpty();
    }

    @Test
    void nameOrderFollowsTheRequest() {
        ExportRequest r = ExportRequest.of(List.of("person:e1"), "week", MON, 360, 1320,
                "a4", "landscape", "en", "last");
        assertThat(only(build(r, world())).title()).isEqualTo("Müller Anna");
    }

    // --- overnight & clipping ------------------------------------------------

    @Test
    void splitsAnOvernightShiftAcrossMidnight() {
        World w = world();
        World night = new World(w.employees(),
                List.of(pos("p1", "Kitchen", 2, shift("s1", MON, 1320, 360, 1))),
                assign("s1@2026-07-27", "e1"));
        // Full-day band, so both halves survive the clip.
        ExportRequest r = ExportRequest.of(List.of("position:p1"), "week", MON, 0, 1440,
                "a4", "landscape", "en", "first");
        List<ExportDocument.Segment> segs = only(build(r, night)).segments();
        assertThat(segs).extracting(ExportDocument.Segment::day, ExportDocument.Segment::start,
                        ExportDocument.Segment::end)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(0, 1320, 1440),
                        org.assertj.core.groups.Tuple.tuple(1, 0, 360));
        // Both halves still read as the shift's real hours, not the clipped slice's.
        assertThat(segs).allMatch(s -> s.time().equals("22:00–06:00"));
    }

    @Test
    void spillsAnOvernightTailFromTheDayBeforeTheRange() {
        World w = world();
        World lead = new World(w.employees(),
                List.of(pos("p1", "Kitchen", 2, shift("s1", MON.minusDays(1), 1320, 360, 1))),
                assign("s1@2026-07-26", "e1"));
        ExportRequest r = ExportRequest.of(List.of("position:p1"), "week", MON, 0, 1440,
                "a4", "landscape", "en", "first");
        ExportDocument.Section s = only(build(r, lead));
        // Only the tail is visible; the head belongs to the Sunday before this page.
        assertThat(s.segments()).extracting(ExportDocument.Segment::day, ExportDocument.Segment::start,
                        ExportDocument.Segment::end)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(0, 0, 360));
        // …and it is not reported as dropped: it was never this page's business.
        assertThat(s.dropped().count()).isZero();
    }

    @Test
    void trimsAShiftThatStraddlesThePrintedBand() {
        World w = world();
        World early = new World(w.employees(),
                List.of(pos("p1", "Kitchen", 2, shift("s1", MON, 300, 480, 1))), // 05:00–08:00
                assign("s1@2026-07-27", "e1"));
        ExportDocument.Section s = only(build(req("week", "position:p1"), early));
        assertThat(s.segments()).hasSize(1);
        assertThat(s.segments().get(0).start()).isEqualTo(360); // clipped to 06:00
        assertThat(s.segments().get(0).time()).isEqualTo("05:00–08:00"); // still says the truth
        assertThat(s.dropped().count()).isZero();
    }

    @Test
    void reportsShiftsThePrintedHoursLeaveOutEntirely() {
        World w = world();
        World night = new World(w.employees(),
                List.of(pos("p1", "Kitchen", 2, shift("s1", MON, 60, 300, 1))), // 01:00–05:00
                assign("s1@2026-07-27", "e1"));
        ExportDocument.Section s = only(build(req("week", "position:p1"), night));
        assertThat(s.segments()).isEmpty();
        assertThat(s.dropped().count()).isEqualTo(1);
        assertThat(s.dropped().label()).isEqualTo(
                "1 shift falls outside 06:00–22:00 and is not on this page:");
        assertThat(s.dropped().items()).containsExactly("Mon Jul 27 · 01:00–05:00 · Anna Müller");
    }

    // --- overlaps ------------------------------------------------------------

    @Test
    void packsOverlappingShiftsIntoSideBySideLanes() {
        World w = world();
        World two = new World(w.employees(),
                List.of(pos("p1", "Kitchen", 2,
                        shift("s1", MON, 480, 960, 1), shift("s2", MON, 600, 1080, 1))),
                assign("s1@2026-07-27", "e1", "s2@2026-07-27", "e2"));
        List<ExportDocument.Segment> segs = only(build(req("week", "position:p1"), two)).segments();
        assertThat(segs).hasSize(2);
        assertThat(segs).allMatch(s -> s.lanes() == 2);
        assertThat(segs).extracting(ExportDocument.Segment::lane).containsExactlyInAnyOrder(0, 1);
    }

    @Test
    void leavesBackToBackShiftsInOneLane() {
        World w = world();
        World two = new World(w.employees(),
                List.of(pos("p1", "Kitchen", 2,
                        shift("s1", MON, 480, 720, 1), shift("s2", MON, 720, 960, 1))),
                assign("s1@2026-07-27", "e1", "s2@2026-07-27", "e2"));
        assertThat(only(build(req("week", "position:p1"), two)).segments())
                .allMatch(s -> s.lanes() == 1 && s.lane() == 0);
    }

    // --- month view ----------------------------------------------------------

    @Test
    void aMonthPagePrintsWholeDaysAndDimsTheNeighbouringMonth() {
        ExportDocument doc = build(req("month", "position:p1"), world());
        ExportDocument.Section s = only(doc);
        assertThat(s.grid().dayStart()).isZero();
        assertThat(s.grid().dayEnd()).isEqualTo(1440);
        assertThat(s.range()).isEqualTo("July 2026");
        assertThat(s.days()).hasSize(35);
        assertThat(s.days().get(0).dim()).isTrue();   // Jun 29
        assertThat(s.days().get(2).dim()).isFalse();  // Jul 1
        assertThat(s.days().get(2).sub()).isEqualTo("Jul"); // only the 1st names its month
        assertThat(s.days().get(3).sub()).isEmpty();
    }

    @Test
    void aMonthCellSummarisesItsShiftsAndSaysHowManyItHid() {
        List<ShiftTemplate> many = new ArrayList<>();
        Map<String, List<String>> a = new LinkedHashMap<>();
        for (int i = 0; i < 6; i++) {
            many.add(shift("s" + i, MON, 480 + i * 30, 960, 1));
            a.put("s" + i + "@2026-07-27", List.of("e1"));
        }
        World w = new World(world().employees(),
                List.of(pos("p1", "Kitchen", 2, many.toArray(new ShiftTemplate[0]))), a);
        ExportDocument.Section s = only(build(req("month", "position:p1"), w));
        ExportDocument.Day mon = s.days().stream()
                .filter(d -> d.date().equals("2026-07-27")).findFirst().orElseThrow();
        assertThat(mon.chips()).hasSize(4);
        assertThat(mon.chips().get(0).time()).isEqualTo("08:00");
        assertThat(mon.more()).isEqualTo(2);
        assertThat(mon.moreLabel()).isEqualTo("+2 more");
    }

    // --- weekends, legend, hour labels ---------------------------------------

    @Test
    void dimsTheWeekendInAWeekView() {
        assertThat(only(build(req("week", "position:p1"), world())).days())
                .extracting(ExportDocument.Day::dim)
                .containsExactly(false, false, false, false, false, true, true);
    }

    @Test
    void printsALegendOnlyWhenThePageMixesSeveralLabelledThings() {
        World w = world();
        assertThat(only(build(req("week", "position:p1"), w)).legend()).isEmpty();

        World twoPositions = new World(w.employees(),
                List.of(pos("p1", "Kitchen", 2, shift("s1", MON, 480, 960, 1)),
                        pos("p2", "Bar", 5, shift("s2", MON, 1020, 1320, 1))),
                assign("s1@2026-07-27", "e1", "s2@2026-07-27", "e1"));
        assertThat(only(build(req("week", "person:e1"), twoPositions)).legend())
                .extracting(ExportDocument.LegendEntry::label).containsExactly("Kitchen", "Bar");
    }

    @Test
    void labelsEveryHourLineTheTemplateWillDraw() {
        ExportDocument.Section s = only(build(req("week", "position:p1"), world()));
        assertThat(s.grid().hourLabels()).containsEntry("6", "06:00").containsEntry("22", "22:00");
        assertThat(s.grid().hourLabels()).hasSize(17);
    }

    @Test
    void closesAFullDayAxisAtTwentyFour() {
        ExportRequest r = ExportRequest.of(List.of("position:p1"), "week", MON, 0, 1440,
                "a4", "landscape", "en", "first");
        assertThat(only(build(r, world())).grid().hourLabels())
                .containsEntry("0", "00:00").containsEntry("24", "24:00");
    }

    // --- batch & German ------------------------------------------------------

    @Test
    void buildsOneSectionPerScopeInRequestOrder() {
        World w = world();
        ExportDocument doc = build(req("week", "person:e2", "position:p1", "person:e1"), w);
        assertThat(doc.sections()).extracting(ExportDocument.Section::title)
                .containsExactly("Ben Ott", "Kitchen", "Anna Müller");
    }

    @Test
    void skipsAnUnknownScopeButFailsWhenNothingIsLeft() {
        World w = world();
        assertThat(build(req("week", "person:nope", "position:p1"), w).sections()).hasSize(1);
        assertThatThrownBy(() -> build(req("week", "person:nope"), w))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no such person or position");
    }

    @Test
    void localisesLabelsAndDatesForGerman() {
        ExportRequest r = ExportRequest.of(List.of("position:p1"), "week", MON, 60, 300,
                "a4", "landscape", "de", "first");
        World w = world();
        ExportDocument doc = build(r, new World(w.employees(),
                List.of(pos("p1", "Küche", 2, shift("s1", MON, 480, 960, 2))),
                assign("s1@2026-07-27", "e1")));
        ExportDocument.Section s = only(doc);
        assertThat(s.days().get(0).head()).isEqualTo("Mo");     // no trailing dot
        assertThat(s.stats()).extracting(ExportDocument.Stat::k)
                .containsExactly("Besetzte Plätze", "Offene Plätze");
        assertThat(doc.meta().generated()).startsWith("Erstellt");
        assertThat(s.dropped().label()).contains("liegt ausserhalb von 01:00–05:00");
    }

    // --- request normalisation -----------------------------------------------

    @Test
    void normalisesNonsenseRequestParameters() {
        ExportRequest r = ExportRequest.of(List.of("position:p1"), "fortnight", null,
                1400, 120, "papyrus", "sideways", null, null);
        assertThat(r.view()).isEqualTo("week");
        assertThat(r.paper()).isEqualTo("a4");
        assertThat(r.orientation()).isEqualTo("landscape");
        assertThat(r.anchor()).isEqualTo(LocalDate.now());
        assertThat(r.dayEnd() - r.dayStart()).isGreaterThanOrEqualTo(ExportRequest.MIN_BAND_MINUTES);
    }

    @Test
    void standsADayPageUpButLaysTheOthersDown() {
        // A single day column has no use for a landscape sheet's width.
        assertThat(ExportRequest.of(List.of("position:p1"), "day", MON, null, null, null, null, null, null)
                .orientation()).isEqualTo("portrait");
        for (String view : new String[] { "week", "month" }) {
            assertThat(ExportRequest.of(List.of("position:p1"), view, MON, null, null, null, null, null, null)
                    .orientation()).as(view).isEqualTo("landscape");
        }
        // An explicit choice still wins.
        assertThat(ExportRequest.of(List.of("position:p1"), "day", MON, null, null, null, "landscape", null, null)
                .orientation()).isEqualTo("landscape");
    }

    @Test
    void aMonthRequestIgnoresAnyTimeBand() {
        ExportRequest r = ExportRequest.of(List.of("position:p1"), "month", MON, 360, 1320,
                "a4", "landscape", "en", "first");
        assertThat(r.dayStart()).isZero();
        assertThat(r.dayEnd()).isEqualTo(1440);
    }

    @Test
    void rejectsMissingAndMalformedScopes() {
        assertThatThrownBy(() -> ExportRequest.of(List.of(), "week", MON, 360, 1320,
                null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one scope");
        assertThatThrownBy(() -> ExportRequest.of(List.of("team:p1"), "week", MON, 360, 1320,
                null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown scope kind");
        assertThatThrownBy(() -> ExportRequest.of(List.of("p1"), "week", MON, 360, 1320,
                null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("person:<id>");
    }

    @Test
    void boundsTheSizeOfABatch() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i <= ExportRequest.MAX_SCOPES; i++) many.add("person:e" + i);
        assertThatThrownBy(() -> ExportRequest.of(many, "week", MON, 360, 1320, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("too many scopes");
    }

    // --- palette -------------------------------------------------------------

    @Test
    void mirrorsTheFrontendPalette() {
        // The values frontend/src/theme.js `colorAt` produces for the same indices.
        assertThat(Palette.colorAt(0)).isEqualTo(new ExportDocument.Color(0.56, 0.13, 0.0));
        assertThat(Palette.colorAt(3)).isEqualTo(new ExportDocument.Color(0.62, 0.155, 52.5));
        assertThat(Palette.colorAt(-4)).isEqualTo(Palette.colorAt(0)); // negatives clamp
    }
}
