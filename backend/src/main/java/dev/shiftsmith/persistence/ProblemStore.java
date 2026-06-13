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

import java.util.Optional;

/**
 * Read gateway for rehydrating the whole problem from the normalized rows on boot, so
 * the in-memory {@code ScheduleService} (and the solver) can work over it. Every
 * <em>write</em> goes through the granular per-resource stores ({@code EmployeeStore},
 * {@code PositionStore}, {@code SettingsStore}, {@code AssignmentStore}) — there is no
 * document-shaped write or JSONB blob any more (issue #47, Phase 7).
 */
@ApplicationScoped
public class ProblemStore {

    /** The persisted problem, or empty on a fresh database (no settings/employees/positions yet). */
    @Transactional
    public Optional<LoadedProblem> load() {
        if (isNormalizedEmpty()) return Optional.empty();
        return Optional.of(readNormalized());
    }

    /** Seed the singleton settings row on a fresh database so granular settings writes have a target. */
    @Transactional
    public void ensureEmptySettings() {
        if (SettingsEntity.findById(SettingsEntity.SINGLETON_ID) == null) {
            SettingsEntity s = new SettingsEntity();
            s.id = SettingsEntity.SINGLETON_ID;
            s.horizonUnit = "week";
            s.horizonCount = 1;
            s.persist();
        }
    }

    /** True when no problem has been persisted into the normalized tables yet. */
    private boolean isNormalizedEmpty() {
        return SettingsEntity.findById(SettingsEntity.SINGLETON_ID) == null
                && EmployeeEntity.count() == 0
                && PositionEntity.count() == 0;
    }

    /** Read the normalized rows back into the domain (deterministic, id-ordered). */
    private LoadedProblem readNormalized() {
        ProblemMapper.Tables t = new ProblemMapper.Tables();
        t.settings = SettingsEntity.findById(SettingsEntity.SINGLETON_ID);
        t.skills.addAll(SkillEntity.<SkillEntity>listAll(Sort.by("ordinal")));
        t.employees.addAll(EmployeeEntity.<EmployeeEntity>listAll(Sort.by("id")));
        t.blocks.addAll(AvailabilityBlockEntity.<AvailabilityBlockEntity>listAll(Sort.by("id")));
        t.rules.addAll(WorkRuleEntity.<WorkRuleEntity>listAll(Sort.by("id")));
        t.positions.addAll(PositionEntity.<PositionEntity>listAll(Sort.by("id")));
        t.templates.addAll(ShiftTemplateEntity.<ShiftTemplateEntity>listAll(Sort.by("id")));
        t.assignments.addAll(AssignmentEntity.<AssignmentEntity>listAll(Sort.by("templateId", "occurrenceDate", "slotIndex")));
        return ProblemMapper.toLoadedProblem(t);
    }
}
