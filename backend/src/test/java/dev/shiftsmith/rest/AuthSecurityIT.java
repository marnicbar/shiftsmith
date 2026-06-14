package dev.shiftsmith.rest;

import dev.shiftsmith.auth.AuthService;
import dev.shiftsmith.support.EnabledIfDockerAvailable;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

/**
 * Security regression tests for issue #32: a long-lived bearer token must never be
 * accepted as a query parameter on a non-stream endpoint, and changing a password
 * must invalidate previously issued tokens. Boots the real stack against PostgreSQL,
 * so it is gated on a database. Uses a dedicated account so it never disturbs the
 * shared {@code admin} login other @QuarkusTest classes rely on.
 */
@QuarkusTest
@EnabledIfDockerAvailable
class AuthSecurityIT {

    @Inject
    AuthService auth;

    private static final String USER = "sec-user";

    private String login(String password) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("username", USER, "password", password, "remember", false))
                .when().post("/api/auth/login").then().statusCode(200).extract().path("token");
    }

    @BeforeEach
    void seedAccount() {
        // Reset to a known password every run (idempotent create, then force the hash).
        auth.createUser(USER, "secret123", "manager", null);
        // Ensure the password is the expected one even if a prior run changed it.
        // changePassword is a no-op if the current password doesn't match, so try both.
        auth.changePassword(USER, "secret456", "secret123");
    }

    @Test
    void queryParamTokenIsRejectedOnNonStreamEndpoints() {
        String token = login("secret123");

        // The header is honored...
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/auth/me").then().statusCode(200).body("username", is(USER));

        // ...but the same token as a ?token= query param is rejected everywhere but the stream.
        given().when().get("/api/auth/me?token=" + token).then().statusCode(401);
        given().when().get("/api/employees?token=" + token).then().statusCode(401);
    }

    @Test
    void changingPasswordInvalidatesExistingTokens() {
        String token = login("secret123");
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/auth/me").then().statusCode(200);

        // Rotate the password out from under the issued token.
        auth.changePassword(USER, "secret123", "secret456");

        // The old token no longer verifies (its fingerprint no longer matches the hash).
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/auth/me").then().statusCode(401);

        // A fresh login with the new password works.
        String fresh = login("secret456");
        given().header("Authorization", "Bearer " + fresh)
                .when().get("/api/auth/me").then().statusCode(200);
    }
}
