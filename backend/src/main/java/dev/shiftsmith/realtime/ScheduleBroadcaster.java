package dev.shiftsmith.realtime;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Fan-out hub for typed change events (issue #47, Phase 5), used to push live updates
 * to every connected browser over Server-Sent Events.
 *
 * <p>It carries only a tiny {@link ChangeEvent}, never the schedule payload: events are
 * emitted from request threads and the Timefold solver thread, so emitting must not take
 * locks or do heavy work. Clients refetch just the affected slice (an SSE delta), instead
 * of every subscriber rebuilding a full snapshot on every solver tick (the #38 contention).
 */
@ApplicationScoped
public class ScheduleBroadcaster {

    private final BroadcastProcessor<ChangeEvent> processor = BroadcastProcessor.create();

    /** Signal that the solver advanced or its status changed (refetch the live schedule). */
    public void fire() {
        processor.onNext(ChangeEvent.solver());
    }

    /** Emit a specific typed change event. */
    public void emit(ChangeEvent event) {
        processor.onNext(event);
    }

    /** Stream of change events; one item per {@link #fire()}/{@link #emit}. */
    public Multi<ChangeEvent> events() {
        return processor;
    }
}
