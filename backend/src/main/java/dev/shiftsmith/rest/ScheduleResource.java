package dev.shiftsmith.rest;

import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.EmployeeSchedule;
import dev.shiftsmith.domain.Shift;
import dev.shiftsmith.service.ScheduleService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/schedule")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ScheduleResource {

    @Inject
    ScheduleService service;

    @GET
    public EmployeeSchedule getSchedule() {
        return service.getSchedule();
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

    @POST
    @Path("/employees")
    public Response addEmployee(Employee employee) {
        service.addEmployee(employee);
        return Response.status(Response.Status.CREATED).entity(employee).build();
    }

    @PUT
    @Path("/employees/{name}")
    public Response updateEmployee(@PathParam("name") String name, Employee employee) {
        service.updateEmployee(name, employee);
        return Response.ok(employee).build();
    }

    @DELETE
    @Path("/employees/{name}")
    public Response removeEmployee(@PathParam("name") String name) {
        service.removeEmployee(name);
        return Response.noContent().build();
    }

    @POST
    @Path("/shifts")
    public Response addShift(Shift shift) {
        service.addShift(shift);
        return Response.status(Response.Status.CREATED).entity(shift).build();
    }

    @PUT
    @Path("/shifts/{id}")
    public Response updateShift(@PathParam("id") String id, Shift shift) {
        service.updateShift(id, shift);
        return Response.ok(shift).build();
    }

    @DELETE
    @Path("/shifts/{id}")
    public Response removeShift(@PathParam("id") String id) {
        service.removeShift(id);
        return Response.noContent().build();
    }
}
