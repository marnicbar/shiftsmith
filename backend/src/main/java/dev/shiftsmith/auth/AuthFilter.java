package dev.shiftsmith.auth;

import dev.shiftsmith.rest.dto.ApiError;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.Optional;

/**
 * Gates every {@code /api/*} endpoint behind a valid session token. The only
 * exception is {@code POST /api/auth/login}, which mints the token in the first
 * place.
 *
 * <p>The token is read from the {@code Authorization: Bearer} header for normal
 * requests, or from a {@code ?token=} query parameter for the SSE stream (the
 * browser's {@code EventSource} cannot set headers). The authenticated username
 * is stashed as a request property for resources that need it.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class AuthFilter implements ContainerRequestFilter {

    public static final String USERNAME_PROPERTY = "authUsername";

    @Inject
    AuthService auth;

    @Override
    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        if (path.startsWith("/")) path = path.substring(1);

        // The login endpoint must be reachable without a token.
        if ("POST".equals(ctx.getMethod()) && path.equals("api/auth/login")) return;
        // Only guard the API; static SPA assets are served outside JAX-RS anyway.
        if (!path.startsWith("api/")) return;

        Optional<String> username = auth.verify(extractToken(ctx));
        if (username.isEmpty()) {
            ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new ApiError("Authentication required"))
                    .build());
            return;
        }
        ctx.setProperty(USERNAME_PROPERTY, username.get());
    }

    private String extractToken(ContainerRequestContext ctx) {
        String header = ctx.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        return ctx.getUriInfo().getQueryParameters().getFirst("token");
    }
}
