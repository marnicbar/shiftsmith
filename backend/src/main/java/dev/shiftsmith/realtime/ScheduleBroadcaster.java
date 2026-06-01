package dev.shiftsmith.realtime;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Fan-out hub for "the schedule changed" signals, used to push live updates to
 * every connected browser over Server-Sent Events.
 *
 * <p>It deliberately carries only a lightweight tick rather than the schedule
 * payload: {@link #fire()} is called from the Timefold solver thread, so it must
 * not take any locks or do heavy work. Subscribers rebuild a fresh snapshot off
 * the solver thread (see the SSE endpoint).
 */
@ApplicationScoped
public class ScheduleBroadcaster {

    private final BroadcastProcessor<Long> processor = BroadcastProcessor.create();

    /** Signal that the schedule (problem, assignments or solver status) changed. */
    public void fire() {
        processor.onNext(System.nanoTime());
    }

    /** Stream of change ticks; one item per {@link #fire()}. */
    public Multi<Long> ticks() {
        return processor;
    }
}
