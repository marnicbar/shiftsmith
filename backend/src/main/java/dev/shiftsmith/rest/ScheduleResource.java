package dev.shiftsmith.rest;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.SolverStatus;
import dev.shiftsmith.domain.Schedule;
import dev.shiftsmith.domain.ShiftAssignment;
import dev.shiftsmith.rest.dto.ProblemDTO;
import dev.shiftsmith.rest.dto.ScheduleDTO;
import dev.shiftsmith.service.ScheduleService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;
import java.util.List;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ScheduleResource {

    @Inject
    ScheduleService service;

    /** Full state: problem data + the solver's current best assignment + status. */
    @GET
    @Path("/schedule")
    public ScheduleDTO getSchedule() {
        ScheduleDTO dto = new ScheduleDTO();
        dto.employees = service.getEmployees();
        dto.positions = service.getPositions();
        dto.settings = service.getSettings();
        dto.overrides = service.getOverrides();

        List<ShiftAssignment> assignments = service.currentAssignments();
        dto.assignments = assignments.stream().map(ScheduleDTO.Slot::of).toList();
        dto.total = assignments.size();
        dto.staffed = (int) assignments.stream().filter(a -> a.getEmployee() != null).count();
        dto.unassigned = dto.total - dto.staffed;

        LocalDate today = LocalDate.now();
        dto.horizonStart = service.getSettings().horizonStart(today);
        dto.horizonEnd = service.getSettings().horizonEnd(today);

        SolverStatus status = service.status();
        dto.solverStatus = status == null ? "NOT_SOLVING" : status.name();

        Schedule best = service.getBestSolution();
        if (best != null && best.getScore() != null) {
            HardMediumSoftScore s = best.getScore();
            dto.score = new ScheduleDTO.Score(s.hardScore(), s.mediumScore(), s.softScore());
        }
        return dto;
    }

    /** Replace the problem (employees / positions / settings / overrides) and re-solve. */
    @PUT
    @Path("/problem")
    public Response replaceProblem(ProblemDTO dto) {
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
