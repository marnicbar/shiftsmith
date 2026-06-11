package dev.shiftsmith.domain;

import dev.shiftsmith.support.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static dev.shiftsmith.support.Fixtures.MON;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Server-side rejection of duplicate entity IDs — the backend guard that a stale,
 * replayed, or non-UI {@code PUT /api/problem} can't bypass. IDs share one global
 * space, so a collision anywhere (even across types) is reported.
 */
class DuplicateIdTest {

    private Block blockWithId(String id) {
        Block b = Fixtures.window("pref", MON, 600, 720);
        b.setId(id);
        return b;
    }

    @Test
    @DisplayName("flags two employees that share an id")
    void duplicateEmployeeIdRejected() {
        Optional<String> dup = DuplicateId.firstDuplicate(
                List.of(Fixtures.employee("e1"), Fixtures.employee("e1")), List.of());
        assertThat(dup).isPresent();
        assertThat(dup.get()).contains("e1");
    }

    @Test
    @DisplayName("flags a block colliding with an employee id (one global id space)")
    void crossTypeCollisionRejected() {
        Employee a = Fixtures.employee("x1");
        Employee b = Fixtures.employee("e2");
        b.getBlocks().add(blockWithId("x1")); // re-uses the employee's id
        assertThat(DuplicateId.firstDuplicate(List.of(a, b), List.of())).isPresent();
    }

    @Test
    @DisplayName("flags duplicate block ids within one employee")
    void duplicateBlockIdRejected() {
        Employee e = Fixtures.employee("e1");
        e.getBlocks().add(blockWithId("b1"));
        e.getBlocks().add(blockWithId("b1"));
        assertThat(DuplicateId.firstDuplicate(List.of(e), List.of())).isPresent();
    }

    @Test
    @DisplayName("flags a shift id duplicated across two positions")
    void duplicateShiftIdAcrossPositionsRejected() {
        Position p1 = Fixtures.position("p1", "Bar");
        Position p2 = Fixtures.position("p2", "Floor");
        p1.getShifts().add(Fixtures.template("s1", MON, 600, 720, 1));
        p2.getShifts().add(Fixtures.template("s1", MON, 800, 900, 1));
        Optional<String> dup = DuplicateId.firstDuplicate(List.of(), List.of(p1, p2));
        assertThat(dup).isPresent();
        assertThat(dup.get()).contains("s1");
    }

    @Test
    @DisplayName("flags duplicate change ids nested under a rule")
    void duplicateChangeIdRejected() {
        Employee e = Fixtures.employee("e1");
        Rule r = Fixtures.rule("weekHours", "max", 40);
        Change c1 = Fixtures.change(MON, "set", "weekHours", "max", 30);
        Change c2 = Fixtures.change(MON, "set", "weekHours", "max", 20);
        c1.setId("c1");
        c2.setId("c1");
        r.getChanges().add(c1);
        r.getChanges().add(c2);
        e.getRules().add(r);
        assertThat(DuplicateId.firstDuplicate(List.of(e), List.of())).isPresent();
    }

    @Test
    @DisplayName("passes a problem whose ids are all distinct")
    void distinctIdsAccepted() {
        Employee e = Fixtures.employee("e1");
        e.getBlocks().add(blockWithId("b1"));
        Position p = Fixtures.position("p1", "Bar");
        p.getShifts().add(Fixtures.template("s1", MON, 800, 900, 1));
        assertThat(DuplicateId.firstDuplicate(List.of(e), List.of(p))).isEmpty();
    }

    @Test
    @DisplayName("tolerates null lists")
    void nullListsAccepted() {
        assertThat(DuplicateId.firstDuplicate(null, null)).isEmpty();
    }
}
