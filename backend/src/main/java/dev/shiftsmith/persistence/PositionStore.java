package dev.shiftsmith.persistence;

import dev.shiftsmith.domain.Position;
import dev.shiftsmith.domain.ShiftTemplate;
import dev.shiftsmith.persistence.entity.PositionEntity;
import dev.shiftsmith.persistence.entity.ShiftTemplateEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Granular, concurrency-safe writes for a single position (issue #47, Phase 4):
 * create / replace / delete one {@code position} aggregate (the row plus its skills
 * and shift templates) without rewriting the whole problem.
 *
 * <p>Unlike an employee's blocks/rules, a position's shift templates are FK targets of
 * the durable {@code assignment} table, so a replace <em>upserts templates by id</em>
 * (matching the bulk merge): only templates dropped from the position are deleted —
 * taking their assignment rows with them (issue #39) — while surviving templates are
 * updated in place so their history is preserved. Versioning works exactly as for the
 * employee resource (expected version → {@link Result#CONFLICT} on mismatch).
 */
@ApplicationScoped
public class PositionStore {

    public enum Result { OK, NOT_FOUND, CONFLICT, DUPLICATE }

    public record Outcome(Result result, long version) {
        static Outcome of(Result r) { return new Outcome(r, 0); }
        static Outcome ok(long version) { return new Outcome(Result.OK, version); }
    }

    @Transactional
    public Optional<Long> versionOf(String id) {
        PositionEntity p = PositionEntity.findById(id);
        return p == null ? Optional.empty() : Optional.of(p.version);
    }

    @Transactional
    public Outcome create(Position position) {
        if (PositionEntity.findById(position.getId()) != null) return Outcome.of(Result.DUPLICATE);
        PositionEntity p = new PositionEntity();
        p.id = position.getId();
        p.version = 0;
        applyScalar(p, position);
        p.persist();
        for (ShiftTemplate t : position.getShifts()) ProblemMapper.templateToEntity(t, position.getId()).persist();
        return Outcome.ok(0);
    }

    @Transactional
    public Outcome update(Position position, long expectedVersion) {
        PositionEntity p = PositionEntity.findById(position.getId());
        if (p == null) return Outcome.of(Result.NOT_FOUND);
        if (p.version != expectedVersion) return Outcome.of(Result.CONFLICT);
        applyScalar(p, position);
        p.version = expectedVersion + 1;
        upsertTemplates(position);
        return Outcome.ok(p.version);
    }

    @Transactional
    public Outcome delete(String id, long expectedVersion) {
        PositionEntity p = PositionEntity.findById(id);
        if (p == null) return Outcome.of(Result.NOT_FOUND);
        if (p.version != expectedVersion) return Outcome.of(Result.CONFLICT);
        p.delete(); // cascades position skills + shift templates → their assignment rows
        return Outcome.ok(expectedVersion);
    }

    /** Upsert this position's templates by id, preserving the assignment rows of survivors. */
    private void upsertTemplates(Position position) {
        Set<String> keep = new HashSet<>();
        for (ShiftTemplate t : position.getShifts()) keep.add(t.getId());
        if (keep.isEmpty()) {
            ShiftTemplateEntity.delete("positionId", position.getId());
        } else {
            ShiftTemplateEntity.delete("positionId = ?1 and id not in ?2", position.getId(), keep);
        }
        for (ShiftTemplate t : position.getShifts()) {
            ShiftTemplateEntity src = ProblemMapper.templateToEntity(t, position.getId());
            ShiftTemplateEntity m = ShiftTemplateEntity.findById(t.getId());
            if (m == null) { src.persist(); continue; }
            m.positionId = src.positionId;
            m.name = src.name;
            m.anchorDate = src.anchorDate;
            m.startMin = src.startMin;
            m.endMin = src.endMin;
            m.headcount = src.headcount;
            m.repeat = src.repeat;
            m.untilDate = src.untilDate;
            m.days = src.days;
            m.skills.clear();
            m.skills.addAll(src.skills);
            m.exceptions.clear();
            m.exceptions.addAll(src.exceptions);
            m.preferred.clear();
            m.preferred.addAll(src.preferred);
        }
    }

    private static void applyScalar(PositionEntity p, Position position) {
        p.name = position.getName();
        p.color = position.getColor();
        p.group = position.getGroup();
        p.skills = position.getSkills() == null ? new HashSet<>() : new HashSet<>(position.getSkills());
    }
}
