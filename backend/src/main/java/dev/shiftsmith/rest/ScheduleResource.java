package dev.shiftsmith.rest;

import dev.shiftsmith.realtime.ChangeEvent;
import dev.shiftsmith.realtime.ScheduleBroadcaster;
import dev.shiftsmith.rest.dto.ScheduleDTO;
import dev.shiftsmith.service.ScheduleService;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.time.Duration;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ScheduleResource {

    @Inject
    ScheduleService service;

    @Inject
    ScheduleBroadcaster broadcaster;

    /** Full state: problem data + the solver's current best assignment + status. */
    @GET
    @Path("/schedule")
    public ScheduleDTO getSchedule() {
        return service.snapshotDTO();
    }

    /**
     * Live stream of the full state, pushed over Server-Sent Events. Emits the
     * current snapshot immediately, then a fresh snapshot on every change (new
     * best solution, problem edit, solver start/stop), plus a periodic heartbeat
     * to keep the connection alive through proxies.
     *
     * <p>Snapshots are rebuilt off the solver thread (via {@code emitOn}) so the
     * solver is never blocked by serialization or slow clients.
     *
     * <p>{@code @Blocking} so the request runs on a worker thread: the shared
     * {@link dev.shiftsmith.auth.AuthFilter} performs a transactional DB lookup
     * (the seeded-password check), which is illegal on the reactive IO thread a
     * {@code Multi}-returning endpoint would otherwise use. The streaming itself
     * stays off the worker thread — each snapshot is emitted via {@code emitOn}.
     */
    /**
     * Live stream of typed change events over Server-Sent Events (issue #47, Phase 5).
     * Emits a {@code connected} frame immediately, then a small {@link ChangeEvent} per
     * change (a problem edit, a pinned-assignment change, or solver progress), plus a
     * periodic heartbeat. Clients refetch only the affected slice — the stream never
     * carries (or rebuilds) the full snapshot, so the solver's frequent ticks no longer
     * fan a full rebuild out to every subscriber.
     *
     * <p>{@code @Blocking} so the request runs on a worker thread: the shared
     * {@link dev.shiftsmith.auth.AuthFilter} performs a transactional DB lookup (the
     * seeded-password check), which is illegal on the reactive IO thread a
     * {@code Multi}-returning endpoint would otherwise use.
     */
    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @Blocking
    public Multi<ChangeEvent> stream() {
        Multi<ChangeEvent> initial = Multi.createFrom().item(ChangeEvent.connected());

        Multi<ChangeEvent> updates = broadcaster.events()
                .emitOn(Infrastructure.getDefaultWorkerPool());

        Multi<ChangeEvent> heartbeat = Multi.createFrom().ticks().every(Duration.ofSeconds(25))
                .emitOn(Infrastructure.getDefaultWorkerPool())
                .map(tick -> ChangeEvent.heartbeat());

        return Multi.createBy().concatenating().streams(
                initial,
                Multi.createBy().merging().streams(updates, heartbeat));
    }

    @POST
    @Path("/solve")
    public Response startSolving() {
        service.startSolving();
        return Response.noContent().build();
    }

    @DELETE
    @Path("/solve")
    public Response stopSolving() {
        service.stopSolving();
        return Response.noContent().build();
    }
}
