package dev.shiftsmith.persistence;

import dev.shiftsmith.domain.Block;
import dev.shiftsmith.domain.Change;
import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.Rule;
import dev.shiftsmith.domain.ShiftTemplate;
import dev.shiftsmith.persistence.entity.AssignmentEntity;
import dev.shiftsmith.persistence.entity.EmployeeEntity;
import dev.shiftsmith.persistence.entity.PositionEntity;
import dev.shiftsmith.persistence.entity.SettingsEntity;
import dev.shiftsmith.persistence.entity.SkillEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #47: rehydrating the whole problem from the normalized rows ({@code Tables} →
 * {@link LoadedProblem}) reproduces the solver-visible domain — recurrence, retained
 * rule changes, the global/personal rule split, the skill order, and the overrides
 * rebuilt from pinned assignment rows. Pure object mapping, no database. The reverse
 * direction (domain → entity) is exercised here via the same per-field builders the
 * write stores use ({@code blockToEntity}/{@code ruleToEntity}/{@code templateToEntity}).
 */
class ProblemMapperTest {

    private static final LocalDate MON = LocalDate.of(2026, 6, 1); // a Monday

    private ProblemMapper.Tables richTables() {
        ProblemMapper.Tables t = new ProblemMapper.Tables();

        SettingsEntity se = new SettingsEntity();
        se.id = SettingsEntity.SINGLETON_ID;
        se.horizonUnit = "week";
        se.horizonCount = 1;
        t.settings = se;
        t.skills.add(skill("Bar", 0));
        t.skills.add(skill("Kitchen", 1));

        // Global rule with a retained scheduled change (weekHours max 40 → 36 from MON+7).
        Rule gWeek = rule("g-week", "weekHours", "max", 40);
        gWeek.getChanges().add(change("c-g", MON.plusDays(7), "weekHours", "max", 36));
        t.rules.add(ProblemMapper.ruleToEntity(gWeek, null));

        // Alice: skills, a pref window, a recurring undesired window (days {2,4}, except MON+2),
        // a multi-day vacation span, and a personal day-hours rule with a scheduled change.
        t.employees.add(employee("alice", "Alice", "Adams", "server", 80, Set.of("Bar")));
        t.blocks.add(ProblemMapper.blockToEntity(window("pref", MON, 480, 1200, "none", null, null, null), "alice"));
        Block weekly = window("undes", MON, 1080, 1320, "weekly", MON.plusDays(60), Set.of(2, 4), Set.of(MON.plusDays(2)));
        t.blocks.add(ProblemMapper.blockToEntity(weekly, "alice"));
        Block vac = window("vac", MON, 0, 0, "none", null, null, null);
        vac.setAllDay(true);
        vac.setEndDate(MON.plusDays(3));
        t.blocks.add(ProblemMapper.blockToEntity(vac, "alice"));
        Rule dayMax = rule("a-day", "dayHours", "max", 8);
        dayMax.getChanges().add(change("a-c", MON.plusDays(1), "dayHours", "max", 10));
        t.rules.add(ProblemMapper.ruleToEntity(dayMax, "alice"));

        t.employees.add(employee("bob", "Bob", "", "", 0, Set.of("Kitchen")));

        PositionEntity p = new PositionEntity();
        p.id = "p1"; p.name = "Bar"; p.color = 3; p.group = "front"; p.skills = new HashSet<>(Set.of("Bar"));
        t.positions.add(p);
        ShiftTemplate t1 = new ShiftTemplate();
        t1.setId("t1"); t1.setDate(MON); t1.setStart(1020); t1.setEnd(1440);
        t1.setHeadcount(2); t1.setRepeat("weekly"); t1.setDays(Set.of(0)); t1.setSkills(new HashSet<>(Set.of("Bar")));
        t.templates.add(ProblemMapper.templateToEntity(t1, "p1"));

        // Pinned occurrence: slot 0 = alice, slot 1 = pinned-but-empty.
        t.assignments.add(pin("t1", MON, 0, "alice"));
        t.assignments.add(pin("t1", MON, 1, null));
        return t;
    }

    @Test
    void rehydratesSettingsSkillsAndGlobalRules() {
        LoadedProblem p = ProblemMapper.toLoadedProblem(richTables());
        assertThat(p.settings().getHorizonUnit()).isEqualTo("week");
        assertThat(p.settings().getSkills()).containsExactly("Bar", "Kitchen"); // ordered by ordinal
        assertThat(p.settings().getGlobalRules()).hasSize(1);
        Rule g = p.settings().getGlobalRules().get(0);
        assertThat(g.effectiveAt(MON).value()).isEqualTo(40);            // retained change, resolved per-date
        assertThat(g.effectiveAt(MON.plusDays(7)).value()).isEqualTo(36);
    }

    @Test
    void rehydratesEmployeesAvailabilityAndRules() {
        LoadedProblem p = ProblemMapper.toLoadedProblem(richTables());
        assertThat(p.employees()).extracting(Employee::getId).containsExactly("alice", "bob");
        Employee alice = p.employees().stream().filter(e -> e.getId().equals("alice")).findFirst().orElseThrow();
        assertThat(alice.getFirstName()).isEqualTo("Alice");
        assertThat(alice.getContract()).isEqualTo(80);
        assertThat(alice.getSkills()).containsExactly("Bar");

        assertThat(alice.isAvailableFor(MON, 480, 1200)).isTrue();
        assertThat(alice.undesiredMinutes(MON.plusDays(2), 1080, 1320)).isZero();      // exception day
        assertThat(alice.undesiredMinutes(MON.plusDays(4), 1080, 1320)).isEqualTo(240); // Wed in days {2,4}
        assertThat(alice.isOnVacation(MON.plusDays(3))).isTrue();
        assertThat(alice.maxLimit("dayHours", MON)).isEqualTo(8);
        assertThat(alice.maxLimit("dayHours", MON.plusDays(1))).isEqualTo(10);          // scheduled change
    }

    @Test
    void rebuildsOverridesFromPinnedAssignmentRows() {
        LoadedProblem p = ProblemMapper.toLoadedProblem(richTables());
        assertThat(p.overrides()).containsEntry("t1@" + MON, java.util.Arrays.asList("alice", null));
    }

    // --- builders -------------------------------------------------------

    private static SkillEntity skill(String name, int ordinal) {
        SkillEntity s = new SkillEntity(); s.name = name; s.ordinal = ordinal; return s;
    }

    private static EmployeeEntity employee(String id, String fn, String ln, String role, int contract, Set<String> skills) {
        EmployeeEntity e = new EmployeeEntity();
        e.id = id; e.firstName = fn; e.lastName = ln; e.role = role; e.contract = contract;
        e.skills = new HashSet<>(skills);
        return e;
    }

    private static AssignmentEntity pin(String templateId, LocalDate date, int slot, String employeeId) {
        AssignmentEntity a = new AssignmentEntity();
        a.templateId = templateId; a.occurrenceDate = date; a.slotIndex = slot; a.employeeId = employeeId;
        a.startTs = date.atTime(17, 0); a.endTs = date.plusDays(1).atStartOfDay();
        a.pinned = true; a.source = "manual";
        return a;
    }

    private static Block window(String type, LocalDate date, int start, int end, String repeat,
                                LocalDate until, Set<Integer> days, Set<LocalDate> except) {
        Block b = new Block();
        b.setId("blk-" + type + "-" + date + "-" + start);
        b.setType(type); b.setDate(date); b.setStart(start); b.setEnd(end); b.setRepeat(repeat);
        b.setUntil(until); b.setDays(days); b.setExcept(except);
        return b;
    }

    private static Rule rule(String id, String metric, String op, int value) {
        Rule r = new Rule(); r.setId(id); r.setMetric(metric); r.setOp(op); r.setValue(value); return r;
    }

    private static Change change(String id, LocalDate date, String metric, String op, int value) {
        Change c = new Change(); c.setId(id); c.setDate(date); c.setKind("set");
        c.setMetric(metric); c.setOp(op); c.setValue(value); return c;
    }
}
