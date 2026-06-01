package dev.shiftsmith.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Persistence gateway for the single problem document. Survives restarts so the
 * in-memory {@code ScheduleService} can rehydrate instead of re-seeding demo data.
 */
@ApplicationScoped
public class ProblemStore {

    @Transactional
    public Optional<ProblemDocument> load() {
        ProblemEntity e = ProblemEntity.findById(ProblemEntity.SINGLETON_ID);
        return Optional.ofNullable(e == null ? null : e.document);
    }

    /** Upsert the one-and-only problem row. */
    @Transactional
    public void save(ProblemDocument doc) {
        ProblemEntity e = ProblemEntity.findById(ProblemEntity.SINGLETON_ID);
        if (e == null) {
            e = new ProblemEntity();
            e.id = ProblemEntity.SINGLETON_ID;
        }
        e.document = doc;
        e.updatedAt = Instant.now();
        e.persist();
    }
}
