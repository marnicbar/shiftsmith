package dev.shiftsmith.persistence;

import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.Position;
import dev.shiftsmith.domain.Settings;
import dev.shiftsmith.domain.ShiftTemplate;
import dev.shiftsmith.persistence.entity.AssignmentEntity;
import dev.shiftsmith.persistence.entity.EmployeeEntity;
import dev.shiftsmith.persistence.entity.ShiftTemplateEntity;
import dev.shiftsmith.support.EnabledIfDockerAvailable;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static dev.shiftsmith.support.Fixtures.availableAllDay;
import static dev.shiftsmith.support.Fixtures.employee;
import static dev.shiftsmith.support.Fixtures.position;
import static dev.shiftsmith.support.Fixtures.template;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 of issue #47: {@link ProblemStore#save} merges by id so the durable
 * {@code assignment} history survives a document edit, instead of being
 * cascade-pruned by a delete-and-reinsert. Boots the real persistence stack against
 * a Dev Services / local PostgreSQL, so it is gated on a database being available.
 */
@QuarkusTest
@EnabledIfDockerAvailable
class ProblemStoreMergeIT {

    @Inject
    ProblemStore store;

    private final LocalDate today = LocalDate.now();

    private ProblemDocument document(String firstName, boolean withTemplate) {
        ProblemDocument doc = new ProblemDocument();
        Employee alice = availableAllDay(employee("alice", "Bar"), today);
        alice.setFirstName(firstName);
        doc.employees = List.of(alice);

        Position p = position("p1", "Bar");
        if (withTemplate) {
            ShiftTemplate t = template("t1", today, 1020, 1440, 1, "Bar");
            p.getShifts().add(t);
        }
        doc.positions = List.of(p);
        doc.settings = new Settings("day", 1);
        doc.overrides = Map.of();
        return doc;
    }

    /** Seed a past solver assignment row — the kind a prior solve would leave as history. */
    private void seedPastHistoryRow() {
        QuarkusTransaction.requiringNew().run(() -> {
            AssignmentEntity ae = new AssignmentEntity();
            ae.templateId = "t1";
            ae.occurrenceDate = today.minusDays(30);
            ae.slotIndex = 0;
            ae.startTs = today.minusDays(30).atTime(17, 0);
            ae.endTs = today.minusDays(29).atStartOfDay();
            ae.employeeId = "alice";
            ae.pinned = false;
            ae.source = "solver";
            ae.persist();
        });
    }

    @Test
    void editKeepingTemplatePreservesAssignmentHistory() {
        store.save(document("Alice", true));
        seedPastHistoryRow();

        // An unrelated edit (rename) that keeps the template must not drop the history.
        store.save(document("Alicia", true));

        QuarkusTransaction.requiringNew().run(() -> {
            assertThat(AssignmentEntity.count("occurrenceDate < ?1", today)).isEqualTo(1);
            EmployeeEntity alice = EmployeeEntity.findById("alice");
            assertThat(alice).isNotNull();
            assertThat(alice.firstName).isEqualTo("Alicia");        // upserted in place
            assertThat(ShiftTemplateEntity.<ShiftTemplateEntity>count("id = ?1", "t1")).isEqualTo(1);
        });
    }

    @Test
    void removingTemplateCascadePrunesItsAssignments() {
        store.save(document("Alice", true));
        seedPastHistoryRow();

        // Dropping the template removes it and its assignment rows (issue #39), but the
        // person stays.
        store.save(document("Alice", false));

        QuarkusTransaction.requiringNew().run(() -> {
            assertThat(AssignmentEntity.count()).isZero();
            assertThat(ShiftTemplateEntity.count()).isZero();
            assertThat(EmployeeEntity.<EmployeeEntity>count("id = ?1", "alice")).isEqualTo(1);
        });
    }
}
