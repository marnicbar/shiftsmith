package dev.shiftsmith.persistence;

import dev.shiftsmith.persistence.entity.AssignmentEntity;
import dev.shiftsmith.persistence.entity.AvailabilityBlockEntity;
import dev.shiftsmith.persistence.entity.EmployeeEntity;
import dev.shiftsmith.persistence.entity.PositionEntity;
import dev.shiftsmith.persistence.entity.SettingsEntity;
import dev.shiftsmith.persistence.entity.ShiftTemplateEntity;
import dev.shiftsmith.persistence.entity.SkillEntity;
import dev.shiftsmith.persistence.entity.WorkRuleEntity;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Persistence gateway for the editable problem. Reads and writes the normalized,
 * time-indexed tables (issue #47) through {@link ProblemMapper}, while preserving
 * the document-shaped {@code load()/save()} contract the in-memory
 * {@code ScheduleService} relies on. The schema itself is owned by Flyway.
 *
 * <p>On the first boot after the normalization migration, an existing single-row
 * JSONB {@code problem} document is migrated into normalized rows once (guarded by
 * the normalized side being empty); a fresh database starts empty.
 */
@ApplicationScoped
public class ProblemStore {

    private static final Logger LOG = Logger.getLogger(ProblemStore.class);

    /**
     * Load the persisted problem, or empty on a fresh database. If the normalized
     * tables are empty but a legacy JSONB {@code problem} document is present, it is
     * migrated into normalized rows once and returned.
     */
    @Transactional
    public Optional<ProblemDocument> load() {
        if (isNormalizedEmpty()) {
            Optional<ProblemDocument> legacy = loadLegacyDocument();
            if (legacy.isPresent()) {
                LOG.info("Migrating legacy JSONB problem document into normalized rows");
                save(legacy.get());
                return legacy;
            }
            return Optional.empty();
        }
        return Optional.of(readNormalized());
    }

    /**
     * Persist the whole problem by <em>merging</em> it into the normalized rows.
     *
     * <p>The FK targets of the durable {@code assignment} table — {@code employee},
     * {@code position} and {@code shift_template} — are upserted by id so their rows
     * (and therefore the solver-produced assignment history hanging off them) survive
     * an edit instead of being cascade-pruned by a delete-and-reinsert. Entities not
     * referenced by {@code assignment} (settings, skills, blocks, rules) are simply
     * replaced. A removed template/position prunes its assignment rows via cascade
     * (issue #39); a removed employee leaves its past slots unstaffed (FK SET NULL).
     */
    @Transactional
    public void save(ProblemDocument doc) {
        ProblemMapper.Tables tables = ProblemMapper.toTables(doc);
        Set<String> keepEmployees = ids(tables.employees, e -> e.id);
        Set<String> keepPositions = ids(tables.positions, p -> p.id);
        Set<String> keepTemplates = ids(tables.templates, t -> t.id);

        // Delete only the rows that vanished, child-first, via bulk queries so each row
        // is removed exactly once and the DB's ON DELETE CASCADE prunes the dependents
        // (a removed template/position takes its assignment rows with it — issue #39; a
        // removed employee leaves its past slots unstaffed via SET NULL). Doing this with
        // managed-entity deletes would double-delete cascaded rows (no JPA association
        // encodes the order) and trip Hibernate's optimistic row-count check.
        if (keepTemplates.isEmpty()) ShiftTemplateEntity.deleteAll();
        else ShiftTemplateEntity.delete("id not in ?1", keepTemplates);
        if (keepPositions.isEmpty()) PositionEntity.deleteAll();
        else PositionEntity.delete("id not in ?1", keepPositions);
        if (keepEmployees.isEmpty()) EmployeeEntity.deleteAll();
        else EmployeeEntity.delete("id not in ?1", keepEmployees);

        upsertSettings(tables.settings);
        SkillEntity.deleteAll();
        for (SkillEntity s : tables.skills) s.persist();
        upsertPositions(tables.positions);   // before templates: FK target
        upsertTemplates(tables.templates);
        upsertEmployees(tables.employees);   // before blocks/rules: FK target
        AvailabilityBlockEntity.deleteAll();
        for (AvailabilityBlockEntity b : tables.blocks) b.persist();
        WorkRuleEntity.deleteAll();          // personal + global
        for (WorkRuleEntity r : tables.rules) r.persist();
        reconcileManualPins(tables.assignments, keepEmployees);
    }

    private void upsertSettings(SettingsEntity in) {
        if (in == null) return;
        SettingsEntity managed = SettingsEntity.findById(in.id);
        if (managed == null) {
            in.persist();
        } else {
            managed.horizonUnit = in.horizonUnit;
            managed.horizonCount = in.horizonCount;
        }
    }

    private void upsertEmployees(List<EmployeeEntity> incoming) {
        for (EmployeeEntity in : incoming) {
            EmployeeEntity m = EmployeeEntity.findById(in.id);
            if (m == null) { in.persist(); continue; }
            m.firstName = in.firstName;
            m.lastName = in.lastName;
            m.role = in.role;
            m.contract = in.contract;
            m.skills.clear();
            m.skills.addAll(in.skills);
        }
    }

    private void upsertPositions(List<PositionEntity> incoming) {
        for (PositionEntity in : incoming) {
            PositionEntity m = PositionEntity.findById(in.id);
            if (m == null) { in.persist(); continue; }
            m.name = in.name;
            m.color = in.color;
            m.group = in.group;
            m.skills.clear();
            m.skills.addAll(in.skills);
        }
    }

    private void upsertTemplates(List<ShiftTemplateEntity> incoming) {
        for (ShiftTemplateEntity in : incoming) {
            ShiftTemplateEntity m = ShiftTemplateEntity.findById(in.id);
            if (m == null) { in.persist(); continue; }
            m.positionId = in.positionId;
            m.name = in.name;
            m.anchorDate = in.anchorDate;
            m.startMin = in.startMin;
            m.endMin = in.endMin;
            m.headcount = in.headcount;
            m.repeat = in.repeat;
            m.untilDate = in.untilDate;
            m.days = in.days;
            m.skills.clear();
            m.skills.addAll(in.skills);
            m.exceptions.clear();
            m.exceptions.addAll(in.exceptions);
            m.preferred.clear();
            m.preferred.addAll(in.preferred);
        }
    }


    /**
     * Reconcile the manual-pin rows ({@code source = manual}) from the overrides, without
     * disturbing the solver's history. A pin may only reference a person who still exists
     * (the assignment FK is enforced); a dangling reference is nulled so the slot persists
     * unstaffed rather than failing the write. Any prior row for a pinned slot — including
     * a stale solver pick — is cleared so the pin takes over.
     */
    private void reconcileManualPins(List<AssignmentEntity> pins, Set<String> knownEmployees) {
        AssignmentEntity.delete("source = ?1", "manual");
        for (AssignmentEntity pin : pins) {
            if (pin.employeeId != null && !knownEmployees.contains(pin.employeeId)) pin.employeeId = null;
            AssignmentEntity.delete("templateId = ?1 and occurrenceDate = ?2 and slotIndex = ?3",
                    pin.templateId, pin.occurrenceDate, pin.slotIndex);
            pin.persist();
        }
    }

    private static <T> Set<String> ids(List<T> items, java.util.function.Function<T, String> id) {
        Set<String> out = new HashSet<>();
        for (T item : items) out.add(id.apply(item));
        return out;
    }

    /** True when no problem has been persisted into the normalized tables yet. */
    private boolean isNormalizedEmpty() {
        return SettingsEntity.findById(SettingsEntity.SINGLETON_ID) == null
                && EmployeeEntity.count() == 0
                && PositionEntity.count() == 0;
    }

    /** Read the normalized rows back into a document (deterministic, id-ordered). */
    private ProblemDocument readNormalized() {
        ProblemMapper.Tables t = new ProblemMapper.Tables();
        t.settings = SettingsEntity.findById(SettingsEntity.SINGLETON_ID);
        t.skills.addAll(SkillEntity.<SkillEntity>listAll(Sort.by("ordinal")));
        t.employees.addAll(EmployeeEntity.<EmployeeEntity>listAll(Sort.by("id")));
        t.blocks.addAll(AvailabilityBlockEntity.<AvailabilityBlockEntity>listAll(Sort.by("id")));
        t.rules.addAll(WorkRuleEntity.<WorkRuleEntity>listAll(Sort.by("id")));
        t.positions.addAll(PositionEntity.<PositionEntity>listAll(Sort.by("id")));
        t.templates.addAll(ShiftTemplateEntity.<ShiftTemplateEntity>listAll(Sort.by("id")));
        t.assignments.addAll(AssignmentEntity.<AssignmentEntity>listAll(Sort.by("templateId", "occurrenceDate", "slotIndex")));
        return ProblemMapper.toDocument(t);
    }

    private Optional<ProblemDocument> loadLegacyDocument() {
        ProblemEntity e = ProblemEntity.findById(ProblemEntity.SINGLETON_ID);
        return Optional.ofNullable(e == null ? null : e.document);
    }
}
