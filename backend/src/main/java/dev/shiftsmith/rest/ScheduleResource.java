package dev.shiftsmith.rest;

import dev.shiftsmith.domain.CalendarOverlap;
import dev.shiftsmith.domain.DuplicateId;
import dev.shiftsmith.realtime.ScheduleBroadcaster;
import dev.shiftsmith.rest.dto.ApiError;
import dev.shiftsmith.rest.dto.ProblemDTO;
import dev.shiftsmith.rest.dto.ScheduleDTO;
import dev.shiftsmith.service.ScheduleService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.time.Duration;
import java.util.Optional;

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
     */
    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<ScheduleDTO> stream() {
        Multi<ScheduleDTO> initial = Multi.createFrom().item(service::snapshotDTO);

        Multi<ScheduleDTO> updates = broadcaster.ticks()
                .emitOn(Infrastructure.getDefaultWorkerPool())
                .map(tick -> service.snapshotDTO());

        Multi<ScheduleDTO> heartbeat = Multi.createFrom().ticks().every(Duration.ofSeconds(25))
                .emitOn(Infrastructure.getDefaultWorkerPool())
                .map(tick -> service.snapshotDTO());

        return Multi.createBy().concatenating().streams(
                initial,
                Multi.createBy().merging().streams(updates, heartbeat));
    }

    /** Replace the problem (employees / positions / settings / overrides) and re-solve. */
    @PUT
    @Path("/problem")
    public Response replaceProblem(ProblemDTO dto) {
        Optional<String> duplicate = DuplicateId.firstDuplicate(dto.employees, dto.positions);
        if (duplicate.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiError(duplicate.get()))
                    .build();
        }
        Optional<String> conflict = CalendarOverlap.firstConflict(dto.employees, dto.positions);
        if (conflict.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiError(conflict.get()))
                    .build();
        }
        service.replaceProblem(dto.employees, dto.positions, dto.settings, dto.overrides);
        return Response.noContent().build();
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
