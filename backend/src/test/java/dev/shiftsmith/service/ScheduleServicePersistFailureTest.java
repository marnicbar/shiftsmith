package dev.shiftsmith.service;

import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.Settings;
import dev.shiftsmith.persistence.PersistFailedException;
import dev.shiftsmith.persistence.ProblemDocument;
import dev.shiftsmith.persistence.ProblemStore;
import dev.shiftsmith.realtime.ScheduleBroadcaster;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dev.shiftsmith.support.Fixtures.employee;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A persist failure must not be silently swallowed: {@code replaceProblem} has to
 * propagate it (so the REST layer can answer 503) and leave the in-memory state
 * untouched, so the client never mistakes a failed write for a durable edit.
 */
class ScheduleServicePersistFailureTest {

    /** A store whose save always blows up, simulating the database being unavailable. */
    static class FailingStore extends ProblemStore {
        @Override
        public void save(ProblemDocument doc) {
            throw new RuntimeException("database down");
        }
    }

    private ScheduleService serviceWithFailingStore() {
        ScheduleService service = new ScheduleService();
        service.store = new FailingStore();
        service.broadcaster = new ScheduleBroadcaster();
        return service;
    }

    @Test
    void replaceProblemPropagatesPersistFailure() {
        ScheduleService service = serviceWithFailingStore();
        Employee alice = employee("alice", "Bar");

        assertThatThrownBy(() -> service.replaceProblem(
                List.of(alice), List.of(), new Settings("week", 1), Map.of()))
                .isInstanceOf(PersistFailedException.class);
    }

    @Test
    void replaceProblemLeavesInMemoryStateUntouchedWhenPersistFails() {
        ScheduleService service = serviceWithFailingStore();
        assertThat(service.getEmployees()).isEmpty();

        Employee alice = employee("alice", "Bar");
        try {
            service.replaceProblem(List.of(alice), List.of(), new Settings("week", 1), Map.of());
        } catch (PersistFailedException expected) {
            // expected — the write failed
        }

        // The edit must not have been committed in memory; otherwise it would diverge
        // from the database and be lost on the next restart.
        assertThat(service.getEmployees()).isEmpty();
    }
}
