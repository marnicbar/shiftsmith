package dev.shiftsmith.service;

import dev.shiftsmith.domain.Block;
import dev.shiftsmith.domain.Change;
import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.Position;
import dev.shiftsmith.domain.Rule;
import dev.shiftsmith.domain.ShiftTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Seed problem mirroring the frontend's data.js, anchored to the current week
 * (Monday = day offset 0). Loaded once on startup so a fresh container has the
 * same demo schedule the prototype shipped with.
 */
final class DemoData {

    private DemoData() {}

    private static final LocalDate MON = LocalDate.now().with(DayOfWeek.MONDAY);
    private static int seq = 1;

    private static String uid(String p) { return p + (seq++); }
    private static LocalDate iso(int offset) { return MON.plusDays(offset); }
    private static Set<String> skills(String... s) { return new HashSet<>(Arrays.asList(s)); }

    static void seed(List<Employee> employees, List<Position> positions) {
        employees.addAll(buildEmployees());
        positions.addAll(buildPositions(employees));
    }

    // --- employees -------------------------------------------------------

    private static Block blk(String type, int dayOffset, int start, int end, String repeat, boolean allDay) {
        Block b = new Block();
        b.setId(uid("b"));
        b.setType(type);
        b.setDate(iso(dayOffset));
        b.setStart(start);
        b.setEnd(end);
        b.setRepeat(repeat);
        b.setAllDay(allDay);
        return b;
    }

    private static List<Rule> defaultRules(int contract) {
        List<Rule> rules = new ArrayList<>();
        rules.add(rule("weekHours", "preferred", contract));
        rules.add(rule("weekHours", "max", 48));
        rules.add(rule("dayHours", "max", 10));
        return rules;
    }

    private static Rule rule(String metric, String op, int value) {
        Rule r = new Rule();
        r.setId(uid("r"));
        r.setMetric(metric);
        r.setOp(op);
        r.setValue(value);
        return r;
    }

    private static Employee emp(String name, String role, int contract, Set<String> skills, List<Block> blocks) {
        Employee e = new Employee();
        e.setId(uid("e"));
        e.setName(name);
        e.setRole(role);
        e.setContract(contract);
        e.setSkills(skills);
        e.setBlocks(blocks);
        e.setRules(defaultRules(contract));
        return e;
    }

    private static List<Employee> buildEmployees() {
        List<Employee> list = new ArrayList<>();

        Employee anna = emp("Anna Schmidt", "Senior Staff", 38, skills("Floor", "Bar", "Supervisor"), new ArrayList<>(List.of(
                blk("pref", 0, 9 * 60, 17 * 60, "weekly", false),
                blk("pref", 1, 9 * 60, 17 * 60, "weekly", false),
                blk("undes", 4, 18 * 60, 23 * 60, "weekly", false),
                blk("vac", 2, 0, 0, "none", true))));
        // Anna prefers an 8h day; her preferred week drops to 30h from next month.
        anna.getRules().add(rule("dayHours", "preferred", 8));
        Change drop = new Change();
        drop.setId(uid("c"));
        drop.setDate(LocalDate.now().withDayOfMonth(1).plusMonths(1));
        drop.setKind("set");
        drop.setMetric("weekHours");
        drop.setOp("preferred");
        drop.setValue(30);
        anna.getRules().get(0).getChanges().add(drop);
        list.add(anna);

        list.add(emp("Liam Carter", "Cook", 40, skills("Kitchen", "Logistics"), new ArrayList<>(List.of(
                blk("pref", 0, 7 * 60, 15 * 60, "weekly", false),
                blk("pref", 2, 7 * 60, 15 * 60, "weekly", false),
                blk("pref", 4, 7 * 60, 15 * 60, "weekly", false),
                blk("undes", 5, 0, 0, "weekly", true)))));

        list.add(emp("Mei Tanaka", "Receptionist", 32, skills("Reception", "First Aid"), new ArrayList<>(List.of(
                blk("pref", 1, 8 * 60, 14 * 60, "weekly", false),
                blk("pref", 3, 8 * 60, 14 * 60, "weekly", false),
                blk("vac", 5, 0, 0, "none", true),
                blk("vac", 6, 0, 0, "none", true)))));

        list.add(emp("Omar Haddad", "Bartender", 30, skills("Bar", "Floor"), new ArrayList<>(List.of(
                blk("pref", 3, 16 * 60, 24 * 60, "weekly", false),
                blk("pref", 4, 16 * 60, 24 * 60, "weekly", false),
                blk("pref", 5, 16 * 60, 24 * 60, "weekly", false),
                blk("undes", 0, 7 * 60, 12 * 60, "weekly", false)))));

        list.add(emp("Sofia Rossi", "Facilities", 25, skills("Cleaning", "Logistics"), new ArrayList<>(List.of(
                blk("pref", 0, 6 * 60, 11 * 60, "daily", false)))));

        list.add(emp("Noah Becker", "Shift Lead", 40, skills("Floor", "Supervisor", "First Aid"), new ArrayList<>(List.of(
                blk("pref", 1, 12 * 60, 20 * 60, "weekly", false),
                blk("pref", 2, 12 * 60, 20 * 60, "weekly", false),
                blk("pref", 3, 12 * 60, 20 * 60, "weekly", false),
                blk("undes", 6, 12 * 60, 20 * 60, "weekly", false)))));

        list.add(emp("Priya Nair", "Staff", 28, skills("Reception", "Floor"), new ArrayList<>(List.of(
                blk("pref", 2, 10 * 60, 18 * 60, "weekly", false),
                blk("pref", 4, 10 * 60, 18 * 60, "weekly", false)))));

        return list;
    }

    // --- positions -------------------------------------------------------

    private static ShiftTemplate sh(String name, int dayOffset, int start, int end, Set<String> skills,
                                    int headcount, String repeat) {
        ShiftTemplate t = new ShiftTemplate();
        t.setId(uid("s"));
        t.setName(name);
        t.setDate(iso(dayOffset));
        t.setStart(start);
        t.setEnd(end);
        t.setSkills(skills);
        t.setHeadcount(headcount);
        t.setRepeat(repeat);
        return t;
    }

    private static Position pos(String name, int color, String group, Set<String> skills, List<ShiftTemplate> shifts) {
        Position p = new Position();
        p.setId(uid("p"));
        p.setName(name);
        p.setColor(color);
        p.setGroup(group);
        p.setSkills(skills);
        p.setShifts(shifts);
        return p;
    }

    private static List<Position> buildPositions(List<Employee> employees) {
        List<Position> list = new ArrayList<>();

        list.add(pos("Front Desk", 192, "Front of House", skills("Reception", "First Aid"), new ArrayList<>(List.of(
                sh("Morning Desk", 0, 8 * 60, 14 * 60, skills("Reception"), 1, "weekly"),
                sh("Afternoon Desk", 0, 14 * 60, 20 * 60, skills("Reception"), 1, "weekly"),
                sh("Morning Desk", 1, 8 * 60, 14 * 60, skills("Reception"), 1, "weekly"),
                sh("Afternoon Desk", 2, 14 * 60, 20 * 60, skills("Reception"), 2, "weekly"),
                sh("Morning Desk", 3, 8 * 60, 14 * 60, skills("Reception"), 1, "weekly"),
                sh("Afternoon Desk", 4, 14 * 60, 20 * 60, skills("Reception"), 1, "weekly")))));

        list.add(pos("Main Floor", 274, "Front of House", skills("Floor", "Supervisor"), new ArrayList<>(List.of(
                sh("Open Floor", 0, 9 * 60, 15 * 60, skills("Floor"), 2, "weekly"),
                sh("Peak Floor", 0, 15 * 60, 22 * 60, skills("Floor", "Supervisor"), 3, "weekly"),
                sh("Peak Floor", 1, 15 * 60, 22 * 60, skills("Floor"), 3, "weekly"),
                sh("Open Floor", 2, 9 * 60, 15 * 60, skills("Floor"), 2, "weekly"),
                sh("Peak Floor", 3, 15 * 60, 22 * 60, skills("Floor"), 2, "weekly"),
                sh("Peak Floor", 4, 15 * 60, 22 * 60, skills("Floor"), 3, "weekly"),
                sh("Weekend Floor", 5, 11 * 60, 22 * 60, skills("Floor"), 4, "weekly")))));

        list.add(pos("Kitchen Line", 35, "Kitchen", skills("Kitchen"), new ArrayList<>(List.of(
                sh("Prep", 0, 7 * 60, 12 * 60, skills("Kitchen"), 2, "weekly"),
                sh("Service", 0, 12 * 60, 20 * 60, skills("Kitchen"), 2, "weekly"),
                sh("Prep", 2, 7 * 60, 12 * 60, skills("Kitchen"), 1, "weekly"),
                sh("Service", 2, 12 * 60, 20 * 60, skills("Kitchen"), 2, "weekly"),
                sh("Service", 4, 12 * 60, 20 * 60, skills("Kitchen"), 2, "weekly"),
                sh("Weekend Service", 5, 11 * 60, 21 * 60, skills("Kitchen"), 3, "weekly")))));

        list.add(pos("Bar", 330, "Kitchen", skills("Bar"), new ArrayList<>(List.of(
                sh("Evening Bar", 3, 17 * 60, 24 * 60, skills("Bar"), 1, "weekly"),
                sh("Evening Bar", 4, 17 * 60, 24 * 60, skills("Bar"), 2, "weekly"),
                sh("Weekend Bar", 5, 16 * 60, 24 * 60, skills("Bar"), 2, "weekly")))));

        list.add(pos("Cleaning Crew", 150, "Operations", skills("Cleaning"), new ArrayList<>(List.of(
                sh("Early Clean", 0, 6 * 60, 10 * 60, skills("Cleaning"), 1, "daily"),
                sh("Close Clean", 0, 22 * 60, 24 * 60, skills("Cleaning"), 1, "daily")))));

        list.add(pos("Night Supervisor", 256, "Operations", skills("Supervisor"), new ArrayList<>(List.of(
                sh("Night Lead", 4, 18 * 60, 24 * 60, skills("Supervisor", "First Aid"), 1, "weekly"),
                sh("Night Lead", 5, 18 * 60, 24 * 60, skills("Supervisor"), 1, "weekly")))));

        // Preferred-employee pins (by position + shift name), as in the seed.
        pin(list, employees, "Bar", "Evening Bar", "Omar Haddad");
        pin(list, employees, "Front Desk", "Afternoon Desk", "Mei Tanaka");
        pin(list, employees, "Kitchen Line", "Prep", "Liam Carter");

        return list;
    }

    private static void pin(List<Position> positions, List<Employee> employees, String posName,
                            String shiftName, String empName) {
        String empId = employees.stream().filter(e -> e.getName().equals(empName))
                .map(Employee::getId).findFirst().orElse(null);
        if (empId == null) return;
        for (Position p : positions) {
            if (!p.getName().equals(posName)) continue;
            for (ShiftTemplate t : p.getShifts()) {
                if (t.getName().equals(shiftName) && t.getPreferred().size() < t.getHeadcount()) {
                    t.getPreferred().add(empId);
                }
            }
        }
    }
}
