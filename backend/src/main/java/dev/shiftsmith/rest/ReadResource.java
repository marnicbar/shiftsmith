package dev.shiftsmith.rest;

import dev.shiftsmith.rest.dto.ApiError;
import dev.shiftsmith.service.ScheduleService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;

/**
 * Granular, windowed read endpoints (issue #47, Phase 3). They let the client load
 * only what a view needs — a page of people, one person's availability over the
 * visible range, the assignment slots in a date range — instead of pulling the whole
 * problem and the live snapshot. Reads come from the in-memory canonical problem;
 * the schedule range additionally reads the durable {@code assignment} rows, so it
 * spans history and any persisted future beyond the live solve window.
 *
 * <p>The bulk {@code GET /api/schedule} and the SSE stream are unchanged and still
 * power the live Overview while the frontend migrates to these reads.
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class ReadResource {

    /** Guard against an unbounded range scan from a bad/hostile query. */
    private static final long MAX_RANGE_DAYS = 366;

    @Inject
    ScheduleService service;

    @GET
    @Path("/settings")
    public Response settings() {
        return Response.ok(service.getSettings()).build();
    }

    @GET
    @Path("/skills")
    public Response skills() {
        return Response.ok(service.skills()).build();
    }

    @GET
    @Path("/employees")
    public Response employees(@QueryParam("page") @jakarta.ws.rs.DefaultValue("0") int page,
                              @QueryParam("size") @jakarta.ws.rs.DefaultValue("50") int size) {
        return Response.ok(service.employeesPage(page, size)).build();
    }

    @GET
    @Path("/employees/{id}")
    public Response employee(@PathParam("id") String id) {
        return service.employee(id)
                // Carry the row version as an ETag so the client can make a concurrency-safe
                // edit with If-Match (issue #47, Phase 4).
                .map(e -> Response.ok(e)
                        .tag(new jakarta.ws.rs.core.EntityTag(
                                Long.toString(service.employeeVersion(id).orElse(0L))))
                        .build())
                .orElseGet(ReadResource::notFound);
    }

    @GET
    @Path("/employees/{id}/availability")
    public Response availability(@PathParam("id") String id,
                                 @QueryParam("from") LocalDate from, @QueryParam("to") LocalDate to) {
        Response invalid = validateRange(from, to);
        if (invalid != null) return invalid;
        return service.employeeAvailability(id, from, to)
                .map(blocks -> Response.ok(blocks).build()).orElseGet(ReadResource::notFound);
    }

    @GET
    @Path("/employees/{id}/rules")
    public Response rules(@PathParam("id") String id) {
        return service.employeeRules(id).map(r -> Response.ok(r).build()).orElseGet(ReadResource::notFound);
    }

    @GET
    @Path("/positions")
    public Response positions(@QueryParam("page") @jakarta.ws.rs.DefaultValue("0") int page,
                              @QueryParam("size") @jakarta.ws.rs.DefaultValue("50") int size) {
        return Response.ok(service.positionsPage(page, size)).build();
    }

    @GET
    @Path("/positions/{id}")
    public Response position(@PathParam("id") String id) {
        return service.position(id).map(p -> Response.ok(p).build()).orElseGet(ReadResource::notFound);
    }

    @GET
    @Path("/positions/{id}/shift-templates")
    public Response shiftTemplates(@PathParam("id") String id) {
        return service.positionTemplates(id).map(t -> Response.ok(t).build()).orElseGet(ReadResource::notFound);
    }

    /**
     * Assignment slots in {@code [from, to)}, optionally narrowed by {@code scope}
     * ({@code person:<id>} or {@code position:<id>}; absent/{@code overview} = all).
     */
    @GET
    @Path("/schedule/range")
    public Response scheduleRange(@QueryParam("from") LocalDate from, @QueryParam("to") LocalDate to,
                                  @QueryParam("scope") String scope) {
        Response invalid = validateRange(from, to);
        if (invalid != null) return invalid;
        return Response.ok(service.rangeSlots(from, to, scope)).build();
    }

    private static Response validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiError("from and to are required (ISO dates, e.g. 2026-06-01)")).build();
        }
        if (to.isBefore(from)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiError("to must not be before from")).build();
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiError("range too large (max " + MAX_RANGE_DAYS + " days)")).build();
        }
        return null;
    }

    private static Response notFound() {
        return Response.status(Response.Status.NOT_FOUND).entity(new ApiError("not found")).build();
    }
}
