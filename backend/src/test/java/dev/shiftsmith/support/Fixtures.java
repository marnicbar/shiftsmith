package dev.shiftsmith.support;

import dev.shiftsmith.domain.Block;
import dev.shiftsmith.domain.Change;
import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.Position;
import dev.shiftsmith.domain.Rule;
import dev.shiftsmith.domain.Schedule;
import dev.shiftsmith.domain.ShiftAssignment;
import dev.shiftsmith.domain.ShiftTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * Small, readable builders for the domain model used across the test suite.
 *
 * <p>The whole suite anchors to {@link #MON} (a fixed Monday) so that every test
 * is deterministic and independent of the wall-clock date — unlike the frontend
 * seed data, which floats with "this week".
 */
public final class Fixtures {

    private Fixtures() {}

    /** A fixed Monday used as the anchor for all date-based fixtures. */
    public static final LocalDate MON = LocalDate.of(2026, 6, 1);

    public static LocalDate day(int offset) { return MON.plusDays(offset); }

    // --- employees ------------------------------------------------------

    public static Employee employee(String id, String... skills) {
        Employee e = new Employee();
        e.setId(id);
        e.setFirstName(id);
        e.setSkills(new HashSet<>(Arrays.asList(skills)));
        return e;
    }

    /** Mark the employee available for the whole of {@code date} (a pref window 00:00–24:00). */
    public static Employee availableAllDay(Employee e, LocalDate date) {
        e.getBlocks().add(window("pref", date, 0, 1440));
        return e;
    }

    /** Add an availability/preference/vacation window to an employee. */
    public static Block window(String type, LocalDate date, int startMin, int endMin) {
        Block b = new Block();
        b.setId("blk-" + type + "-" + date + "-" + startMin);
        b.setType(type);
        b.setDate(date);
        b.setStart(startMin);
        b.setEnd(endMin);
        b.setRepeat("none");
        return b;
    }

    public static Block vacation(LocalDate date) {
        Block b = window("vac", date, 0, 0);
        b.setAllDay(true);
        return b;
    }

    public static Rule rule(String metric, String op, int value) {
        Rule r = new Rule();
        r.setId("rule-" + metric + "-" + op);
        r.setMetric(metric);
        r.setOp(op);
        r.setValue(value);
        return r;
    }

    public static Change change(LocalDate date, String kind, String metric, String op, int value) {
        Change c = new Change();
        c.setId("chg-" + date);
        c.setDate(date);
        c.setKind(kind);
        c.setMetric(metric);
        c.setOp(op);
        c.setValue(value);
        return c;
    }

    // --- positions / templates ------------------------------------------

    public static Position position(String id, String name) {
        Position p = new Position();
        p.setId(id);
        p.setName(name);
        return p;
    }

    /** A non-recurring shift template on {@code date}, in minutes-from-midnight. */
    public static ShiftTemplate template(String id, LocalDate date, int startMin, int endMin,
                                         int headcount, String... skills) {
        ShiftTemplate t = new ShiftTemplate();
        t.setId(id);
        t.setDate(date);
        t.setStart(startMin);
        t.setEnd(endMin);
        t.setHeadcount(headcount);
        t.setRepeat("none");
        t.setSkills(new HashSet<>(Arrays.asList(skills)));
        return t;
    }

    // --- assignments (for constraint-level tests) -----------------------

    /**
     * A concrete {@link ShiftAssignment} on {@code date} from {@code startMin} to
     * {@code endMin} (minutes-from-midnight). An overnight end ({@code endMin <= startMin})
     * or an end at midnight ({@code endMin >= 1440}) rolls to the next day, mirroring
     * {@code ScheduleExpander}. Optionally assigned.
     */
    public static ShiftAssignment assignment(String id, LocalDate date, int startMin, int endMin,
                                             Employee assignee, String... requiredSkills) {
        ShiftAssignment a = new ShiftAssignment();
        a.setId(id);
        a.setDate(date);
        a.setStart(date.atTime(startMin / 60, startMin % 60));
        LocalDateTime end;
        if (endMin >= 1440) {
            end = date.plusDays(1).atStartOfDay();
        } else if (endMin <= startMin) {
            end = date.plusDays(1).atTime(endMin / 60, endMin % 60);
        } else {
            end = date.atTime(endMin / 60, endMin % 60);
        }
        a.setEnd(end);
        a.setRequiredSkills(new HashSet<>(Arrays.asList(requiredSkills)));
        a.setPreferredEmployeeIds(new ArrayList<>());
        a.setEmployee(assignee);
        return a;
    }

    public static Schedule schedule(List<Employee> employees, ShiftAssignment... assignments) {
        return new Schedule(new ArrayList<>(employees), new ArrayList<>(Arrays.asList(assignments)));
    }
}
