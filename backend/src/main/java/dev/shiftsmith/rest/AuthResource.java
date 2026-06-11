package dev.shiftsmith.rest;

import dev.shiftsmith.auth.AuthFilter;
import dev.shiftsmith.auth.AuthService;
import dev.shiftsmith.rest.dto.ApiError;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.container.ContainerRequestContext;

import java.util.Optional;

/** Login, session check and password change. Guarded by {@link AuthFilter}. */
@Path("/api/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

    /** Minimum length for a new password set via change-password. */
    private static final int MIN_PASSWORD_LENGTH = 6;

    @Inject
    AuthService auth;

    public record LoginRequest(String username, String password, boolean remember) {}
    public record LoginResponse(String token, String username, boolean mustChangePassword) {}
    public record MeResponse(String username, boolean mustChangePassword) {}
    public record ChangePasswordRequest(String currentPassword, String newPassword) {}

    @POST
    @Path("/login")
    public Response login(LoginRequest req) {
        if (req == null || req.username() == null || req.password() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiError("Username and password are required")).build();
        }
        Optional<String> token = auth.login(req.username(), req.password(), req.remember());
        if (token.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new ApiError("Invalid username or password")).build();
        }
        return Response.ok(new LoginResponse(token.get(), req.username(),
                auth.mustChangePassword(req.username()))).build();
    }

    /** Confirms the caller's token is valid; used by the SPA on startup. */
    @GET
    @Path("/me")
    public MeResponse me(@Context ContainerRequestContext ctx) {
        String username = (String) ctx.getProperty(AuthFilter.USERNAME_PROPERTY);
        return new MeResponse(username, auth.mustChangePassword(username));
    }

    @POST
    @Path("/change-password")
    public Response changePassword(ChangePasswordRequest req, @Context ContainerRequestContext ctx) {
        String username = (String) ctx.getProperty(AuthFilter.USERNAME_PROPERTY);
        if (req == null || req.currentPassword() == null || req.newPassword() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiError("Current and new password are required")).build();
        }
        if (req.newPassword().length() < MIN_PASSWORD_LENGTH) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiError("New password must be at least " + MIN_PASSWORD_LENGTH + " characters")).build();
        }
        if (!auth.changePassword(username, req.currentPassword(), req.newPassword())) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new ApiError("Current password is incorrect")).build();
        }
        return Response.noContent().build();
    }
}
