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
 * <p>The token is read from the {@code Authorization: Bearer} header. The SSE
 * stream ({@code GET /api/stream}) is the sole exception: it also accepts a
 * {@code ?token=} query parameter, because the browser's {@code EventSource}
 * cannot set headers. The query-param fallback is rejected on every other
 * endpoint so a long-lived token never has to ride in a URL (where it would land
 * in access logs and browser history). The authenticated username is stashed as a
 * request property for resources that need it.
 *
 * <p>If the authenticated account still carries a seeded, publicly-known
 * password ({@code mustChangePassword}), every endpoint except the session
 * check and the password-change call is blocked with 403 so a fresh deployment
 * cannot be operated on a known credential until it is rotated.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class AuthFilter implements ContainerRequestFilter {

    public static final String USERNAME_PROPERTY = "authUsername";

    @Inject
    AuthService auth;

    @Inject
    CurrentUser currentUser;

    @Override
    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        if (path.startsWith("/")) path = path.substring(1);

        // The login endpoint must be reachable without a token.
        if ("POST".equals(ctx.getMethod()) && path.equals("api/auth/login")) return;
        // Only guard the API; static SPA assets are served outside JAX-RS anyway.
        if (!path.startsWith("api/")) return;

        Optional<String> username = auth.verify(extractToken(ctx, path));
        if (username.isEmpty()) {
            ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new ApiError("Authentication required"))
                    .build());
            return;
        }
        ctx.setProperty(USERNAME_PROPERTY, username.get());

        // Resolve the account once: its role + linked person drive write authorization
        // (issue #47, Phase 6), and its forced-rotation flag is checked below.
        Optional<UserAccount> account = auth.account(username.get());
        account.ifPresent(a -> currentUser.set(a.username, a.role, a.employeeId));

        // A seeded account on a known password may only reach the endpoints it
        // needs to rotate that password; everything else is blocked until it does.
        if (account.map(UserAccount::mustChange).orElse(false)
                && !path.equals("api/auth/me")
                && !path.equals("api/auth/change-password")) {
            ctx.abortWith(Response.status(Response.Status.FORBIDDEN)
                    .entity(new ApiError("Password change required"))
                    .build());
        }
    }

    private String extractToken(ContainerRequestContext ctx, String path) {
        String header = ctx.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        // The SSE stream is the only endpoint allowed to authenticate via a query
        // parameter (EventSource can't set headers); everywhere else the token must
        // come from the header, so it never leaks into URLs/logs.
        if ("GET".equals(ctx.getMethod()) && path.equals("api/stream")) {
            return ctx.getUriInfo().getQueryParameters().getFirst("token");
        }
        return null;
    }
}
