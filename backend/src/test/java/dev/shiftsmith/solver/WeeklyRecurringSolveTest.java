package dev.shiftsmith.solver;

import dev.shiftsmith.domain.Block;
import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.Position;
import dev.shiftsmith.domain.Schedule;
import dev.shiftsmith.domain.Settings;
import dev.shiftsmith.domain.ShiftAssignment;
import dev.shiftsmith.domain.ShiftTemplate;
import dev.shiftsmith.service.ScheduleExpander;
import dev.shiftsmith.support.SolverHarness;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.shiftsmith.support.Fixtures.MON;
import static dev.shiftsmith.support.Fixtures.employee;
import static dev.shiftsmith.support.Fixtures.position;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces the reported bug: a recurring Mon–Fri availability (08:00–17:00) and a
 * recurring Mon–Fri position (08:00–12:00) should be fully solvable — every weekday
 * slot staffed by the one available person.
 */
class WeeklyRecurringSolveTest {

    private static final Set<Integer> MON_TO_FRI = Set.of(0, 1, 2, 3, 4); // Mon=0 … Fri=4

    @Test
    void weeklyMonToFriAvailabilityFillsWeeklyMonToFriPosition() {
        // Position: weekly Mon–Fri, 08:00–12:00
        ShiftTemplate t = new ShiftTemplate();
        t.setId("morning");
        t.setName("morning");
        t.setDate(MON);
        t.setStart(480);   // 08:00
        t.setEnd(720);     // 12:00
        t.setHeadcount(1);
        t.setRepeat("weekly");
        t.setDays(MON_TO_FRI);
        Position p = position("p", "Floor");
        p.getShifts().add(t);

        // Employee: weekly Mon–Fri availability 08:00–17:00
        Employee alice = employee("alice");
        Block avail = new Block();
        avail.setId("avail");
        avail.setType("pref");
        avail.setDate(MON);
        avail.setStart(480);  // 08:00
        avail.setEnd(1020);   // 17:00
        avail.setRepeat("weekly");
        avail.setDays(MON_TO_FRI);
        alice.getBlocks().add(avail);

        Settings window = new Settings("week", 1); // this week + next
        List<ShiftAssignment> slots = ScheduleExpander.expand(
                List.of(p), List.of(alice), window, Map.of(), MON);
        Schedule solved = SolverHarness.solve(
                new Schedule(new ArrayList<>(List.of(alice)), slots));

        long staffed = solved.getAssignments().stream()
                .filter(a -> a.getEmployee() != null).count();

        assertThat(slots).as("Mon–Fri over the horizon should expand to slots").isNotEmpty();
        assertThat(solved.getScore().hardScore()).as("feasible").isZero();
        assertThat(staffed).as("every weekday slot staffed").isEqualTo(slots.size());
    }
}
