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

    /** Replace the whole problem: wipe the normalized rows and re-insert from {@code doc}. */
    @Transactional
    public void save(ProblemDocument doc) {
        ProblemMapper.Tables tables = ProblemMapper.toTables(doc);
        wipe();

        if (tables.settings != null) tables.settings.persist();
        for (SkillEntity s : tables.skills) s.persist();
        for (EmployeeEntity e : tables.employees) e.persist();
        for (AvailabilityBlockEntity b : tables.blocks) b.persist();
        for (WorkRuleEntity r : tables.rules) r.persist();
        for (PositionEntity p : tables.positions) p.persist();
        for (ShiftTemplateEntity t : tables.templates) t.persist();

        // A manual pin may only reference a person who still exists (the assignment FK
        // is enforced). Drop dangling employee references to null rather than failing
        // the write — the slot persists as unstaffed.
        Set<String> knownEmployees = new HashSet<>();
        for (EmployeeEntity e : tables.employees) knownEmployees.add(e.id);
        for (AssignmentEntity a : tables.assignments) {
            if (a.employeeId != null && !knownEmployees.contains(a.employeeId)) a.employeeId = null;
            a.persist();
        }
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

    /** Delete every normalized row. Child tables are pruned by ON DELETE CASCADE. */
    private void wipe() {
        AssignmentEntity.deleteAll();
        WorkRuleEntity.deleteAll();   // personal + global
        EmployeeEntity.deleteAll();   // cascades skills, availability blocks (+exceptions)
        ShiftTemplateEntity.deleteAll();
        PositionEntity.deleteAll();   // cascades position skills
        SkillEntity.deleteAll();
        SettingsEntity.deleteAll();
    }

    private Optional<ProblemDocument> loadLegacyDocument() {
        ProblemEntity e = ProblemEntity.findById(ProblemEntity.SINGLETON_ID);
        return Optional.ofNullable(e == null ? null : e.document);
    }
}
