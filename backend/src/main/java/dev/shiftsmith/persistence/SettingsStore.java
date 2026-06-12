package dev.shiftsmith.persistence;

import dev.shiftsmith.domain.Rule;
import dev.shiftsmith.domain.Settings;
import dev.shiftsmith.persistence.entity.SettingsEntity;
import dev.shiftsmith.persistence.entity.SkillEntity;
import dev.shiftsmith.persistence.entity.WorkRuleEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Optional;

/**
 * Granular, concurrency-safe write for the singleton settings aggregate (issue #47,
 * Phase 4): the horizon, the skill catalogue and the global working-time rules. Edited
 * together as one resource (PUT /api/settings), gated by an expected version so a
 * concurrent settings change is a {@link Result#CONFLICT}. None of these rows are FK
 * targets of {@code assignment}, so the catalogue and global rules are replaced wholesale.
 */
@ApplicationScoped
public class SettingsStore {

    public enum Result { OK, NOT_FOUND, CONFLICT }

    public record Outcome(Result result, long version) {
        static Outcome of(Result r) { return new Outcome(r, 0); }
        static Outcome ok(long version) { return new Outcome(Result.OK, version); }
    }

    @Transactional
    public Optional<Long> version() {
        SettingsEntity s = SettingsEntity.findById(SettingsEntity.SINGLETON_ID);
        return s == null ? Optional.empty() : Optional.of(s.version);
    }

    @Transactional
    public Outcome update(Settings settings, long expectedVersion) {
        SettingsEntity s = SettingsEntity.findById(SettingsEntity.SINGLETON_ID);
        if (s == null) return Outcome.of(Result.NOT_FOUND);
        if (s.version != expectedVersion) return Outcome.of(Result.CONFLICT);

        s.horizonUnit = settings.getHorizonUnit();
        s.horizonCount = settings.getHorizonCount();
        s.version = expectedVersion + 1;

        SkillEntity.deleteAll();
        int ordinal = 0;
        for (String name : settings.getSkills()) {
            SkillEntity sk = new SkillEntity();
            sk.name = name;
            sk.ordinal = ordinal++;
            sk.persist();
        }

        WorkRuleEntity.delete("employeeId is null"); // global rules only
        for (Rule r : settings.getGlobalRules()) ProblemMapper.ruleToEntity(r, null).persist();

        return Outcome.ok(s.version);
    }
}
