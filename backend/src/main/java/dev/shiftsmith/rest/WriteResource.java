package dev.shiftsmith.rest;

import dev.shiftsmith.domain.CalendarOverlap;
import dev.shiftsmith.domain.DuplicateId;
import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.persistence.EmployeeStore;
import dev.shiftsmith.rest.dto.ApiError;
import dev.shiftsmith.service.ScheduleService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Optional;

/**
 * Granular, concurrency-safe writes per resource (issue #47, Phase 4). Edits to
 * different entities never conflict; a same-entity race is signalled with
 * {@code 409 Conflict} via HTTP conditional requests ({@code If-Match}/{@code ETag})
 * instead of the bulk {@code PUT /api/problem}'s last-write-wins (resolves #31).
 *
 * <p>Only the employee resource is wired here so far — the case the multi-user
 * direction needs first; positions/settings/assignments follow the same pattern. The
 * bulk {@code PUT} remains as a (now deprecated) compatibility layer.
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WriteResource {

    @Inject
    ScheduleService service;

    @POST
    @Path("/employees")
    public Response createEmployee(Employee employee) {
        Response invalid = validate(employee);
        if (invalid != null) return invalid;
        EmployeeStore.Outcome outcome = service.createEmployee(employee);
        if (outcome.result() == EmployeeStore.Result.DUPLICATE) {
            return conflict("An employee with id '" + employee.getId() + "' already exists.");
        }
        return Response.status(Response.Status.CREATED)
                .tag(etag(outcome.version()))
                .entity(employee).build();
    }

    @PUT
    @Path("/employees/{id}")
    public Response updateEmployee(@PathParam("id") String id, @HeaderParam(HttpHeaders.IF_MATCH) String ifMatch,
                                   Employee employee) {
        if (employee.getId() == null) employee.setId(id);
        if (!id.equals(employee.getId())) {
            return badRequest("The employee id in the body must match the URL.");
        }
        Optional<Long> expected = parseVersion(ifMatch);
        if (expected.isEmpty()) return missingIfMatch();
        Response invalid = validate(employee);
        if (invalid != null) return invalid;

        EmployeeStore.Outcome outcome = service.updateEmployee(employee, expected.get());
        return switch (outcome.result()) {
            case OK -> Response.ok(employee).tag(etag(outcome.version())).build();
            case NOT_FOUND -> notFound();
            case CONFLICT -> conflict("This employee was modified by someone else; reload and retry.");
            default -> conflict("Unexpected write outcome.");
        };
    }

    @DELETE
    @Path("/employees/{id}")
    public Response deleteEmployee(@PathParam("id") String id, @HeaderParam(HttpHeaders.IF_MATCH) String ifMatch) {
        Optional<Long> expected = parseVersion(ifMatch);
        if (expected.isEmpty()) return missingIfMatch();
        EmployeeStore.Outcome outcome = service.deleteEmployee(id, expected.get());
        return switch (outcome.result()) {
            case OK -> Response.noContent().build();
            case NOT_FOUND -> notFound();
            case CONFLICT -> conflict("This employee was modified by someone else; reload and retry.");
            default -> conflict("Unexpected write outcome.");
        };
    }

    // --- helpers --------------------------------------------------------

    /** Reuse the same structural checks the bulk PUT runs, scoped to this one employee. */
    private static Response validate(Employee employee) {
        if (employee.getId() == null || employee.getId().isBlank()) {
            return badRequest("An employee is missing an id.");
        }
        List<Employee> one = List.of(employee);
        Optional<String> dup = DuplicateId.firstDuplicate(one, List.of());
        if (dup.isPresent()) return badRequest(dup.get());
        Optional<String> overlap = CalendarOverlap.firstConflict(one, List.of());
        if (overlap.isPresent()) return badRequest(overlap.get());
        return null;
    }

    /** Build a (weak-free) ETag from a row version. */
    private static jakarta.ws.rs.core.EntityTag etag(long version) {
        return new jakarta.ws.rs.core.EntityTag(Long.toString(version));
    }

    /** Parse an {@code If-Match} header (a quoted or bare version) into a number. */
    private static Optional<Long> parseVersion(String ifMatch) {
        if (ifMatch == null) return Optional.empty();
        String trimmed = ifMatch.trim().replace("\"", "").replace("W/", "");
        if (trimmed.isEmpty()) return Optional.empty();
        try {
            return Optional.of(Long.parseLong(trimmed));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Response missingIfMatch() {
        return Response.status(428) // Precondition Required
                .entity(new ApiError("This write requires an If-Match header with the current version (ETag).")).build();
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST).entity(new ApiError(message)).build();
    }

    private static Response notFound() {
        return Response.status(Response.Status.NOT_FOUND).entity(new ApiError("not found")).build();
    }

    private static Response conflict(String message) {
        return Response.status(Response.Status.CONFLICT).entity(new ApiError(message)).build();
    }
}
