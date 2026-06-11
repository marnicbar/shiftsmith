package dev.shiftsmith.persistence;

import dev.shiftsmith.domain.Block;
import dev.shiftsmith.domain.Change;
import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.Position;
import dev.shiftsmith.domain.Rule;
import dev.shiftsmith.domain.Settings;
import dev.shiftsmith.domain.ShiftAssignment;
import dev.shiftsmith.domain.ShiftTemplate;
import dev.shiftsmith.service.ScheduleExpander;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Phase 1 round-trip guarantee (issue #47): translating a problem document into
 * the normalized entity graph and back reproduces the same solver-visible problem.
 *
 * <p>Pure object mapping — no database — so it runs in the standard {@code mvn test}
 * suite. It asserts the document survives field-for-field where it matters and, most
 * importantly, that expanding the round-tripped problem yields the identical concrete
 * slots (the same data a {@code ScheduleDTO} is built from).
 */
class ProblemMapperTest {

    private static final LocalDate MON = LocalDate.of(2026, 6, 1); // a Monday

    private ProblemDocument richDocument() {
        ProblemDocument doc = new ProblemDocument();

        Settings settings = new Settings("week", 1);
        settings.setSkills(List.of("Bar", "Kitchen"));
        Rule globalWeek = rule("g-week", "weekHours", "max", 40);
        globalWeek.getChanges().add(change("c-g", MON.plusDays(7), "set", "weekHours", "max", 36));
        settings.setGlobalRules(List.of(globalWeek));
        doc.settings = settings;

        Employee alice = new Employee();
        alice.setId("alice");
        alice.setFirstName("Alice");
        alice.setLastName("Adams");
        alice.setRole("server");
        alice.setContract(80);
        alice.setSkills(Set.of("Bar"));
        alice.getBlocks().add(window("pref", MON, 480, 1200, "none"));
        Block weekly = window("undes", MON, 1080, 1320, "weekly");
        weekly.setUntil(MON.plusDays(60));
        weekly.setDays(Set.of(2, 4));
        weekly.setExcept(Set.of(MON.plusDays(2)));
        alice.getBlocks().add(weekly);
        Block vac = window("vac", MON, 0, 0, "none");
        vac.setAllDay(true);
        vac.setEndDate(MON.plusDays(3));
        alice.getBlocks().add(vac);
        Rule dayMax = rule("a-day", "dayHours", "max", 8);
        dayMax.getChanges().add(change("a-c", MON.plusDays(1), "set", "dayHours", "max", 10));
        alice.getRules().add(dayMax);

        Employee bob = new Employee();
        bob.setId("bob");
        bob.setFirstName("Bob");
        bob.setSkills(Set.of("Kitchen"));
        bob.getBlocks().add(window("pref", MON, 0, 1440, "none"));

        doc.employees = List.of(alice, bob);

        Position p = new Position();
        p.setId("p1");
        p.setName("Bar");
        p.setColor(3);
        p.setGroup("front");
        p.setSkills(Set.of("Bar"));
        ShiftTemplate t = new ShiftTemplate();
        t.setId("t1");
        t.setName("Evening");
        t.setDate(MON);
        t.setStart(1020);
        t.setEnd(1440);
        t.setHeadcount(2);
        t.setRepeat("weekly");
        t.setDays(Set.of(0));
        t.setUntil(MON.plusDays(60));
        t.setExcept(Set.of(MON.plusDays(7)));
        t.setSkills(Set.of("Bar"));
        t.setPreferred(List.of("bob", "alice"));
        p.getShifts().add(t);
        doc.positions = List.of(p);

        Map<String, List<String>> overrides = new HashMap<>();
        overrides.put("t1@" + MON, List.of("alice")); // pins both slots of the headcount-2 occurrence
        doc.overrides = overrides;
        return doc;
    }

    @Test
    void roundTripPreservesSettingsSkillsAndGlobalRules() {
        ProblemDocument back = ProblemMapper.toDocument(ProblemMapper.toTables(richDocument()));

        assertThat(back.settings.getHorizonUnit()).isEqualTo("week");
        assertThat(back.settings.getHorizonCount()).isEqualTo(1);
        assertThat(back.settings.getSkills()).containsExactly("Bar", "Kitchen");

        assertThat(back.settings.getGlobalRules()).hasSize(1);
        Rule g = back.settings.getGlobalRules().get(0);
        assertThat(g.getMetric()).isEqualTo("weekHours");
        assertThat(g.getOp()).isEqualTo("max");
        // The change is retained, not collapsed: resolves to 40 before, 36 from the change date.
        assertThat(g.effectiveAt(MON).value()).isEqualTo(40);
        assertThat(g.effectiveAt(MON.plusDays(7)).value()).isEqualTo(36);
    }

    @Test
    void roundTripPreservesEmployeesAvailabilityAndRules() {
        ProblemDocument back = ProblemMapper.toDocument(ProblemMapper.toTables(richDocument()));

        assertThat(back.employees).extracting(Employee::getId).containsExactly("alice", "bob");
        Employee alice = back.employees.stream().filter(e -> e.getId().equals("alice")).findFirst().orElseThrow();
        assertThat(alice.getFirstName()).isEqualTo("Alice");
        assertThat(alice.getLastName()).isEqualTo("Adams");
        assertThat(alice.getRole()).isEqualTo("server");
        assertThat(alice.getContract()).isEqualTo(80);
        assertThat(alice.getSkills()).containsExactly("Bar");

        // Availability (pref window), the recurring undesired window with selected days +
        // exception, and the multi-day vacation span must all survive intact.
        assertThat(alice.isAvailableFor(MON, 480, 1200)).isTrue();
        assertThat(alice.preferredMinutes(MON, 600, 660)).isEqualTo(60);
        assertThat(alice.undesiredMinutes(MON.plusDays(2), 1080, 1320)).isZero();   // exception day
        assertThat(alice.undesiredMinutes(MON.plusDays(4), 1080, 1320)).isEqualTo(240); // Wednesday in days {2,4}
        assertThat(alice.isOnVacation(MON.plusDays(3))).isTrue();
        assertThat(alice.isOnVacation(MON.plusDays(4))).isFalse();

        // Personal time-varying rule with its scheduled change.
        assertThat(alice.maxLimit("dayHours", MON)).isEqualTo(8);
        assertThat(alice.maxLimit("dayHours", MON.plusDays(1))).isEqualTo(10);
    }

    @Test
    void roundTripExpandsToIdenticalSlots() {
        ProblemDocument original = richDocument();
        ProblemDocument back = ProblemMapper.toDocument(ProblemMapper.toTables(original));

        Map<String, String> before = expandToSlotMap(original);
        Map<String, String> after = expandToSlotMap(back);
        assertThat(after).isEqualTo(before);
        // The pinned occurrence: slot 0 staffed by alice, slot 1 pinned-but-empty.
        assertThat(before).containsEntry("t1@" + MON + "#0", "alice");
        assertThat(before).containsEntry("t1@" + MON + "#1", "<pinned:null>");
    }

    /** Expand a document and key each slot id → assigned employee (marking pins). */
    private Map<String, String> expandToSlotMap(ProblemDocument doc) {
        List<ShiftAssignment> slots = ScheduleExpander.expand(
                doc.positions, doc.employees, doc.settings, doc.overrides, MON);
        Map<String, String> map = new LinkedHashMap<>();
        for (ShiftAssignment a : slots) {
            String who = a.getEmployee() != null ? a.getEmployee().getId()
                    : (a.isPinned() ? "<pinned:null>" : null);
            map.put(a.getId(), who);
        }
        return map;
    }

    private static Block window(String type, LocalDate date, int start, int end, String repeat) {
        Block b = new Block();
        b.setId("blk-" + type + "-" + date + "-" + start);
        b.setType(type);
        b.setDate(date);
        b.setStart(start);
        b.setEnd(end);
        b.setRepeat(repeat);
        return b;
    }

    private static Rule rule(String id, String metric, String op, int value) {
        Rule r = new Rule();
        r.setId(id);
        r.setMetric(metric);
        r.setOp(op);
        r.setValue(value);
        return r;
    }

    private static Change change(String id, LocalDate date, String kind, String metric, String op, int value) {
        Change c = new Change();
        c.setId(id);
        c.setDate(date);
        c.setKind(kind);
        c.setMetric(metric);
        c.setOp(op);
        c.setValue(value);
        return c;
    }
}
