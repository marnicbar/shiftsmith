package dev.shiftsmith.persistence;

import dev.shiftsmith.domain.Block;
import dev.shiftsmith.domain.Change;
import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.Position;
import dev.shiftsmith.domain.Rule;
import dev.shiftsmith.domain.Settings;
import dev.shiftsmith.domain.ShiftTemplate;
import dev.shiftsmith.persistence.entity.AssignmentEntity;
import dev.shiftsmith.persistence.entity.AvailabilityBlockEntity;
import dev.shiftsmith.persistence.entity.EmployeeEntity;
import dev.shiftsmith.persistence.entity.PositionEntity;
import dev.shiftsmith.persistence.entity.SettingsEntity;
import dev.shiftsmith.persistence.entity.SkillEntity;
import dev.shiftsmith.persistence.entity.ShiftTemplateEntity;
import dev.shiftsmith.persistence.entity.WorkRuleChangeEmbeddable;
import dev.shiftsmith.persistence.entity.WorkRuleEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure, side-effect-free translation between the legacy {@link ProblemDocument}
 * (the deeply-nested editable problem) and the normalized JPA entity graph
 * ({@code dev.shiftsmith.persistence.entity}). Keeping the mapping free of any
 * persistence context makes it unit-testable on plain objects — the Phase 1
 * round-trip guarantee (issue #47): {@code doc → tables → doc} reproduces the same
 * solver-visible problem.
 *
 * <p>The legacy {@code overrides} map ({@code "<templateId>@<date>" → [employeeIds]})
 * is materialized into {@link AssignmentEntity} rows: one pinned, {@code manual} row
 * per headcount slot of the pinned occurrence (mirroring {@code ScheduleExpander}).
 * Orphan overrides whose template no longer exists are dropped (issue #39).
 */
public final class ProblemMapper {

    private ProblemMapper() {}

    /** A flat snapshot of every normalized row that makes up one problem document. */
    public static final class Tables {
        public SettingsEntity settings;
        public final List<SkillEntity> skills = new ArrayList<>();
        public final List<EmployeeEntity> employees = new ArrayList<>();
        public final List<AvailabilityBlockEntity> blocks = new ArrayList<>();
        /** Personal rules (employeeId set) and global rules (employeeId null). */
        public final List<WorkRuleEntity> rules = new ArrayList<>();
        public final List<PositionEntity> positions = new ArrayList<>();
        public final List<ShiftTemplateEntity> templates = new ArrayList<>();
        public final List<AssignmentEntity> assignments = new ArrayList<>();
    }

    // --- Document → entities --------------------------------------------------

    public static Tables toTables(ProblemDocument doc) {
        Tables t = new Tables();
        Settings settings = doc.settings != null ? doc.settings : new Settings();

        SettingsEntity se = new SettingsEntity();
        se.id = SettingsEntity.SINGLETON_ID;
        se.horizonUnit = settings.getHorizonUnit();
        se.horizonCount = settings.getHorizonCount();
        t.settings = se;

        int ordinal = 0;
        for (String name : settings.getSkills()) {
            SkillEntity sk = new SkillEntity();
            sk.name = name;
            sk.ordinal = ordinal++;
            t.skills.add(sk);
        }
        for (Rule r : settings.getGlobalRules()) {
            t.rules.add(ruleToEntity(r, null));
        }

        if (doc.employees != null) {
            for (Employee e : doc.employees) {
                EmployeeEntity ee = new EmployeeEntity();
                ee.id = e.getId();
                ee.firstName = e.getFirstName();
                ee.lastName = e.getLastName();
                ee.role = e.getRole();
                ee.contract = e.getContract();
                ee.skills = e.getSkills() == null ? new HashSet<>() : new HashSet<>(e.getSkills());
                t.employees.add(ee);
                for (Block b : e.getBlocks()) t.blocks.add(blockToEntity(b, e.getId()));
                for (Rule r : e.getRules()) t.rules.add(ruleToEntity(r, e.getId()));
            }
        }

        Map<String, ShiftTemplate> templateById = new HashMap<>();
        if (doc.positions != null) {
            for (Position p : doc.positions) {
                PositionEntity pe = new PositionEntity();
                pe.id = p.getId();
                pe.name = p.getName();
                pe.color = p.getColor();
                pe.group = p.getGroup();
                pe.skills = p.getSkills() == null ? new HashSet<>() : new HashSet<>(p.getSkills());
                t.positions.add(pe);
                for (ShiftTemplate st : p.getShifts()) {
                    templateById.put(st.getId(), st);
                    t.templates.add(templateToEntity(st, p.getId()));
                }
            }
        }

        if (doc.overrides != null) {
            for (Map.Entry<String, List<String>> entry : doc.overrides.entrySet()) {
                t.assignments.addAll(overrideToAssignments(entry.getKey(), entry.getValue(), templateById));
            }
        }
        return t;
    }

    public static AvailabilityBlockEntity blockToEntity(Block b, String employeeId) {
        AvailabilityBlockEntity be = new AvailabilityBlockEntity();
        be.id = b.getId();
        be.employeeId = employeeId;
        be.type = b.getType();
        be.anchorDate = b.getDate();
        be.startMin = b.getStart();
        be.endMin = b.getEnd();
        be.allDay = b.isAllDay();
        be.repeat = b.getRepeat() == null ? "none" : b.getRepeat();
        be.untilDate = b.getUntil();
        be.endDate = b.getEndDate();
        be.days = toArray(b.getDays());
        be.exceptions = b.getExcept() == null ? new HashSet<>() : new HashSet<>(b.getExcept());
        return be;
    }

    public static WorkRuleEntity ruleToEntity(Rule r, String employeeId) {
        WorkRuleEntity re = new WorkRuleEntity();
        re.id = r.getId();
        re.employeeId = employeeId;
        re.metric = r.getMetric();
        re.op = r.getOp();
        re.value = r.getValue();
        re.changes = new ArrayList<>();
        if (r.getChanges() != null) {
            for (Change c : r.getChanges()) {
                WorkRuleChangeEmbeddable ce = new WorkRuleChangeEmbeddable();
                ce.id = c.getId();
                ce.effectiveDate = c.getDate();
                ce.kind = c.getKind();
                ce.metric = c.getMetric();
                ce.op = c.getOp();
                ce.value = c.getValue();
                re.changes.add(ce);
            }
        }
        return re;
    }

    public static ShiftTemplateEntity templateToEntity(ShiftTemplate st, String positionId) {
        ShiftTemplateEntity te = new ShiftTemplateEntity();
        te.id = st.getId();
        te.positionId = positionId;
        te.name = st.getName();
        te.anchorDate = st.getDate();
        te.startMin = st.getStart();
        te.endMin = st.getEnd();
        te.headcount = st.getHeadcount();
        te.repeat = st.getRepeat();
        te.untilDate = st.getUntil();
        te.days = toArray(st.getDays());
        te.skills = st.getSkills() == null ? new HashSet<>() : new HashSet<>(st.getSkills());
        te.exceptions = st.getExcept() == null ? new HashSet<>() : new HashSet<>(st.getExcept());
        te.preferred = st.getPreferred() == null ? new ArrayList<>() : new ArrayList<>(st.getPreferred());
        return te;
    }

    private static List<AssignmentEntity> overrideToAssignments(
            String key, List<String> pins, Map<String, ShiftTemplate> templateById) {
        List<AssignmentEntity> out = new ArrayList<>();
        int at = key.lastIndexOf('@');
        if (at < 0) return out;
        String templateId = key.substring(0, at);
        LocalDate date;
        try {
            date = LocalDate.parse(key.substring(at + 1));
        } catch (RuntimeException e) {
            return out;
        }
        ShiftTemplate st = templateById.get(templateId);
        if (st == null) return out; // orphan override — its template is gone (issue #39)

        LocalDateTime start = occurrenceStart(st, date);
        LocalDateTime end = occurrenceEnd(st, date);
        int headcount = Math.max(1, st.getHeadcount());
        for (int i = 0; i < headcount; i++) {
            AssignmentEntity ae = new AssignmentEntity();
            ae.templateId = templateId;
            ae.occurrenceDate = date;
            ae.slotIndex = i;
            ae.startTs = start;
            ae.endTs = end;
            ae.employeeId = (pins != null && i < pins.size()) ? pins.get(i) : null;
            ae.pinned = true;
            ae.source = "manual";
            out.add(ae);
        }
        return out;
    }

    /** Slot start as a concrete timestamp (mirrors {@code ScheduleExpander}). */
    static LocalDateTime occurrenceStart(ShiftTemplate st, LocalDate d) {
        int startMin = st.getStart();
        return d.atTime(startMin / 60, startMin % 60);
    }

    /** Slot end, rolling overnight/until-midnight ends into the next day (mirrors {@code ScheduleExpander}). */
    static LocalDateTime occurrenceEnd(ShiftTemplate st, LocalDate d) {
        int startMin = st.getStart();
        int endMin = st.getEnd();
        if (endMin >= 1440) return d.plusDays(1).atStartOfDay();
        if (endMin <= startMin) return d.plusDays(1).atTime(endMin / 60, endMin % 60);
        return d.atTime(endMin / 60, endMin % 60);
    }

    // --- entities → Document --------------------------------------------------

    public static ProblemDocument toDocument(Tables t) {
        ProblemDocument doc = new ProblemDocument();

        Settings s = new Settings();
        if (t.settings != null) {
            s.setHorizonUnit(t.settings.horizonUnit);
            s.setHorizonCount(t.settings.horizonCount);
        }
        List<SkillEntity> skills = new ArrayList<>(t.skills);
        skills.sort(Comparator.comparingInt(sk -> sk.ordinal));
        s.setSkills(skills.stream().map(sk -> sk.name).collect(toMutableList()));
        s.setGlobalRules(t.rules.stream()
                .filter(r -> r.employeeId == null)
                .map(ProblemMapper::ruleToDomain)
                .collect(toMutableList()));
        doc.settings = s;

        Map<String, List<AvailabilityBlockEntity>> blocksByEmployee = groupBy(t.blocks, b -> b.employeeId);
        Map<String, List<WorkRuleEntity>> rulesByEmployee = groupBy(
                t.rules.stream().filter(r -> r.employeeId != null).toList(), r -> r.employeeId);

        List<Employee> employees = new ArrayList<>();
        for (EmployeeEntity ee : t.employees) {
            Employee e = new Employee();
            e.setId(ee.id);
            e.setFirstName(ee.firstName);
            e.setLastName(ee.lastName);
            e.setRole(ee.role);
            e.setContract(ee.contract);
            e.setSkills(new HashSet<>(ee.skills));
            List<Block> blocks = new ArrayList<>();
            for (AvailabilityBlockEntity be : blocksByEmployee.getOrDefault(ee.id, List.of())) {
                blocks.add(blockToDomain(be));
            }
            e.setBlocks(blocks);
            List<Rule> rules = new ArrayList<>();
            for (WorkRuleEntity re : rulesByEmployee.getOrDefault(ee.id, List.of())) {
                rules.add(ruleToDomain(re));
            }
            e.setRules(rules);
            employees.add(e);
        }
        doc.employees = employees;

        Map<String, List<ShiftTemplateEntity>> templatesByPosition = groupBy(t.templates, te -> te.positionId);
        List<Position> positions = new ArrayList<>();
        for (PositionEntity pe : t.positions) {
            Position p = new Position();
            p.setId(pe.id);
            p.setName(pe.name);
            p.setColor(pe.color);
            p.setGroup(pe.group);
            p.setSkills(new HashSet<>(pe.skills));
            List<ShiftTemplate> shifts = new ArrayList<>();
            for (ShiftTemplateEntity te : templatesByPosition.getOrDefault(pe.id, List.of())) {
                shifts.add(templateToDomain(te));
            }
            p.setShifts(shifts);
            positions.add(p);
        }
        doc.positions = positions;

        doc.overrides = assignmentsToOverrides(t.assignments);
        return doc;
    }

    private static Block blockToDomain(AvailabilityBlockEntity be) {
        Block b = new Block();
        b.setId(be.id);
        b.setType(be.type);
        b.setDate(be.anchorDate);
        b.setStart(be.startMin);
        b.setEnd(be.endMin);
        b.setAllDay(be.allDay);
        b.setRepeat(be.repeat);
        b.setUntil(be.untilDate);
        b.setEndDate(be.endDate);
        b.setDays(toSet(be.days));
        b.setExcept(be.exceptions == null || be.exceptions.isEmpty() ? null : new HashSet<>(be.exceptions));
        return b;
    }

    private static Rule ruleToDomain(WorkRuleEntity re) {
        Rule r = new Rule();
        r.setId(re.id);
        r.setMetric(re.metric);
        r.setOp(re.op);
        r.setValue(re.value);
        List<Change> changes = new ArrayList<>();
        if (re.changes != null) {
            for (WorkRuleChangeEmbeddable ce : re.changes) {
                Change c = new Change();
                c.setId(ce.id);
                c.setDate(ce.effectiveDate);
                c.setKind(ce.kind);
                c.setMetric(ce.metric);
                c.setOp(ce.op);
                c.setValue(ce.value);
                changes.add(c);
            }
        }
        r.setChanges(changes);
        return r;
    }

    private static ShiftTemplate templateToDomain(ShiftTemplateEntity te) {
        ShiftTemplate st = new ShiftTemplate();
        st.setId(te.id);
        st.setName(te.name);
        st.setDate(te.anchorDate);
        st.setStart(te.startMin);
        st.setEnd(te.endMin);
        st.setHeadcount(te.headcount);
        st.setRepeat(te.repeat);
        st.setUntil(te.untilDate);
        st.setDays(toSet(te.days));
        st.setSkills(new HashSet<>(te.skills));
        st.setExcept(te.exceptions == null || te.exceptions.isEmpty() ? null : new HashSet<>(te.exceptions));
        st.setPreferred(new ArrayList<>(te.preferred));
        return st;
    }

    /**
     * Rebuild the {@code overrides} map from the pinned/manual assignment rows: group
     * by {@code (templateId, occurrenceDate)}, order by slot index and collect the
     * employee ids (nulls preserved up to the highest slot). The result expands to the
     * identical pinned slots as the original map.
     */
    private static Map<String, List<String>> assignmentsToOverrides(List<AssignmentEntity> assignments) {
        Map<String, Map<Integer, String>> bySlot = new LinkedHashMap<>();
        for (AssignmentEntity a : assignments) {
            if (!a.pinned) continue; // Phase 1 stores only pins here; solver rows arrive in Phase 2
            String key = a.templateId + "@" + a.occurrenceDate;
            bySlot.computeIfAbsent(key, k -> new HashMap<>()).put(a.slotIndex, a.employeeId);
        }
        Map<String, List<String>> overrides = new HashMap<>();
        for (Map.Entry<String, Map<Integer, String>> e : bySlot.entrySet()) {
            Map<Integer, String> slots = e.getValue();
            int max = slots.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
            List<String> ids = new ArrayList<>();
            for (int i = 0; i <= max; i++) ids.add(slots.get(i));
            overrides.put(e.getKey(), ids);
        }
        return overrides;
    }

    // --- helpers --------------------------------------------------------------

    private static Integer[] toArray(Set<Integer> s) {
        if (s == null) return null;
        return s.stream().sorted().toArray(Integer[]::new);
    }

    private static Set<Integer> toSet(Integer[] a) {
        if (a == null) return null;
        return new LinkedHashSet<>(Arrays.asList(a));
    }

    private static <K, V> Map<K, List<V>> groupBy(List<V> items, java.util.function.Function<V, K> key) {
        Map<K, List<V>> out = new LinkedHashMap<>();
        for (V item : items) out.computeIfAbsent(key.apply(item), k -> new ArrayList<>()).add(item);
        return out;
    }

    private static <T> java.util.stream.Collector<T, ?, List<T>> toMutableList() {
        return java.util.stream.Collectors.toCollection(ArrayList::new);
    }
}
