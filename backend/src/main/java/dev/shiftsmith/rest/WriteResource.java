package dev.shiftsmith.rest;

import dev.shiftsmith.auth.CurrentUser;
import dev.shiftsmith.domain.Block;
import dev.shiftsmith.domain.CalendarOverlap;
import dev.shiftsmith.domain.DuplicateId;
import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.Position;
import dev.shiftsmith.domain.ProblemValidation;
import dev.shiftsmith.domain.Rule;
import dev.shiftsmith.domain.Settings;
import dev.shiftsmith.persistence.EmployeeStore;
import dev.shiftsmith.persistence.PositionStore;
import dev.shiftsmith.persistence.SettingsStore;
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
 * <p>Authorization (issue #47, Phase 6): the catalogue writes (employees, positions,
 * settings, pins) require a manager/admin; an {@code employee} account may only edit
 * its own calendar via {@code PUT /api/employees/{id}/availability|rules}.
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WriteResource {

    @Inject
    ScheduleService service;

    @Inject
    CurrentUser user;

    @POST
    @Path("/employees")
    public Response createEmployee(Employee employee) {
        Response denied = requireManager();
        if (denied != null) return denied;
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
        Response denied = requireManager();
        if (denied != null) return denied;
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
        Response denied = requireManager();
        if (denied != null) return denied;
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

    // --- positions ------------------------------------------------------

    @POST
    @Path("/positions")
    public Response createPosition(Position position) {
        Response denied = requireManager();
        if (denied != null) return denied;
        Response invalid = validatePosition(position);
        if (invalid != null) return invalid;
        PositionStore.Outcome outcome = service.createPosition(position);
        if (outcome.result() == PositionStore.Result.DUPLICATE) {
            return conflict("A position with id '" + position.getId() + "' already exists.");
        }
        return Response.status(Response.Status.CREATED).tag(etag(outcome.version())).entity(position).build();
    }

    @PUT
    @Path("/positions/{id}")
    public Response updatePosition(@PathParam("id") String id, @HeaderParam(HttpHeaders.IF_MATCH) String ifMatch,
                                   Position position) {
        Response denied = requireManager();
        if (denied != null) return denied;
        if (position.getId() == null) position.setId(id);
        if (!id.equals(position.getId())) return badRequest("The position id in the body must match the URL.");
        Optional<Long> expected = parseVersion(ifMatch);
        if (expected.isEmpty()) return missingIfMatch();
        Response invalid = validatePosition(position);
        if (invalid != null) return invalid;

        PositionStore.Outcome outcome = service.updatePosition(position, expected.get());
        return switch (outcome.result()) {
            case OK -> Response.ok(position).tag(etag(outcome.version())).build();
            case NOT_FOUND -> notFound();
            case CONFLICT -> conflict("This position was modified by someone else; reload and retry.");
            default -> conflict("Unexpected write outcome.");
        };
    }

    @DELETE
    @Path("/positions/{id}")
    public Response deletePosition(@PathParam("id") String id, @HeaderParam(HttpHeaders.IF_MATCH) String ifMatch) {
        Response denied = requireManager();
        if (denied != null) return denied;
        Optional<Long> expected = parseVersion(ifMatch);
        if (expected.isEmpty()) return missingIfMatch();
        PositionStore.Outcome outcome = service.deletePosition(id, expected.get());
        return switch (outcome.result()) {
            case OK -> Response.noContent().build();
            case NOT_FOUND -> notFound();
            case CONFLICT -> conflict("This position was modified by someone else; reload and retry.");
            default -> conflict("Unexpected write outcome.");
        };
    }

    // --- assignment pins ------------------------------------------------

    /**
     * Pin one shift occurrence to the given employees (a manual override). The body is
     * the ordered employee-id array (a null/short entry leaves that slot pinned-empty).
     * Pins are explicit overrides, so this is a deliberate last-write-wins set — no
     * If-Match — but it no longer rides on the bulk problem sync.
     */
    @PUT
    @Path("/assignments/{templateId}/{date}")
    public Response pin(@PathParam("templateId") String templateId, @PathParam("date") java.time.LocalDate date,
                        List<String> employeeIds) {
        Response denied = requireManager();
        if (denied != null) return denied;
        boolean found = service.pinOccurrence(templateId, date, employeeIds == null ? List.of() : employeeIds);
        return found ? Response.noContent().build()
                : notFound();
    }

    @DELETE
    @Path("/assignments/{templateId}/{date}")
    public Response unpin(@PathParam("templateId") String templateId, @PathParam("date") java.time.LocalDate date) {
        Response denied = requireManager();
        if (denied != null) return denied;
        service.unpinOccurrence(templateId, date);
        return Response.noContent().build();
    }

    // --- employee self-service: availability + rules (issue #47, Phase 6) ------
    // An employee account may edit only its own calendar here; a manager/admin may edit
    // anyone's. Manager-only fields (skills/role/contract) are untouched — those go
    // through the full employee write above.

    @PUT
    @Path("/employees/{id}/availability")
    public Response availability(@PathParam("id") String id, List<Block> blocks) {
        Response denied = requireCalendar(id);
        if (denied != null) return denied;
        List<Block> next = blocks == null ? List.of() : blocks;
        Response invalid = validateAvailability(id, next);
        if (invalid != null) return invalid;
        return calendarOutcome(service.updateEmployeeAvailability(id, next));
    }

    @PUT
    @Path("/employees/{id}/rules")
    public Response rules(@PathParam("id") String id, List<Rule> rules) {
        Response denied = requireCalendar(id);
        if (denied != null) return denied;
        return calendarOutcome(service.updateEmployeeRules(id, rules == null ? List.of() : rules));
    }

    private Response calendarOutcome(EmployeeStore.Outcome outcome) {
        return switch (outcome.result()) {
            case OK -> Response.ok().tag(etag(outcome.version())).build();
            case NOT_FOUND -> notFound();
            default -> conflict("This employee was modified by someone else; reload and retry.");
        };
    }

    // --- settings (singleton) -------------------------------------------

    @PUT
    @Path("/settings")
    public Response updateSettings(@HeaderParam(HttpHeaders.IF_MATCH) String ifMatch, Settings settings) {
        Response denied = requireManager();
        if (denied != null) return denied;
        Optional<Long> expected = parseVersion(ifMatch);
        if (expected.isEmpty()) return missingIfMatch();
        Optional<String> invalid = ProblemValidation.firstError(List.of(), List.of(), settings);
        if (invalid.isPresent()) return badRequest(invalid.get());

        SettingsStore.Outcome outcome = service.updateSettings(settings, expected.get());
        return switch (outcome.result()) {
            case OK -> Response.ok(settings).tag(etag(outcome.version())).build();
            case NOT_FOUND -> notFound();
            case CONFLICT -> conflict("Settings were modified by someone else; reload and retry.");
        };
    }

    // --- helpers --------------------------------------------------------

    /** Catalogue writes require a manager/admin; otherwise 403. */
    private Response requireManager() {
        return user.isManager() ? null : forbidden();
    }

    /** Editing a calendar needs manager access, or to be that employee's own account. */
    private Response requireCalendar(String employeeId) {
        return user.canEditCalendar(employeeId) ? null : forbidden();
    }

    /** Structural checks on an availability set (overlaps, duplicate block ids). */
    private static Response validateAvailability(String employeeId, List<Block> blocks) {
        Employee tmp = new Employee();
        tmp.setId(employeeId);
        tmp.setBlocks(new java.util.ArrayList<>(blocks));
        List<Employee> one = List.of(tmp);
        Optional<String> dup = DuplicateId.firstDuplicate(one, List.of());
        if (dup.isPresent()) return badRequest(dup.get());
        Optional<String> overlap = CalendarOverlap.firstConflict(one, List.of());
        if (overlap.isPresent()) return badRequest(overlap.get());
        return null;
    }

    /** Reuse the same structural checks the bulk PUT runs, scoped to this one position. */
    private static Response validatePosition(Position position) {
        if (position.getId() == null || position.getId().isBlank()) {
            return badRequest("A position is missing an id.");
        }
        List<Position> one = List.of(position);
        Optional<String> dup = DuplicateId.firstDuplicate(List.of(), one);
        if (dup.isPresent()) return badRequest(dup.get());
        Optional<String> invalid = ProblemValidation.firstError(List.of(), one, null);
        if (invalid.isPresent()) return badRequest(invalid.get());
        Optional<String> overlap = CalendarOverlap.firstConflict(List.of(), one);
        if (overlap.isPresent()) return badRequest(overlap.get());
        return null;
    }

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

    private static Response forbidden() {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(new ApiError("You don't have permission to make this change.")).build();
    }
}
