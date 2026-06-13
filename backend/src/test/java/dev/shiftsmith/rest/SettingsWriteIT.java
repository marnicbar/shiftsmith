package dev.shiftsmith.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.shiftsmith.support.EnabledIfDockerAvailable;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Phase 4 of issue #47: the singleton settings resource is edited with the same
 * optimistic-concurrency contract (If-Match/ETag → 409). Gated on a database.
 */
@QuarkusTest
@EnabledIfDockerAvailable
class SettingsWriteIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String token() {
        return given().contentType(ContentType.JSON)
                .body(Map.of("username", "admin", "password", "shiftsmith", "remember", false))
                .when().post("/api/auth/login").then().statusCode(200).extract().path("token");
    }

    private io.restassured.specification.RequestSpecification authed() {
        return given().header("Authorization", "Bearer " + token());
    }

    private String body(String unit, int count) throws Exception {
        return MAPPER.writeValueAsString(Map.of(
                "horizonUnit", unit, "horizonCount", count, "skills", java.util.List.of("Bar"), "globalRules", java.util.List.of()));
    }

    @Test
    void updateSettingsWithOptimisticConcurrency() throws Exception {
        // Read the current version rather than assuming one (the instance is shared with other ITs).
        String etag0 = authed().when().get("/api/settings").then().statusCode(200).extract().header("ETag");

        String etag1 = authed().contentType(ContentType.JSON).header("If-Match", etag0).body(body("week", 3))
                .when().put("/api/settings").then().statusCode(200).body("horizonCount", is(3)).extract().header("ETag");
        assertThat(etag1).isNotEqualTo(etag0);

        // The stale version is refused; a write with no If-Match is rejected outright.
        authed().contentType(ContentType.JSON).header("If-Match", etag0).body(body("week", 1))
                .when().put("/api/settings").then().statusCode(409);
        authed().contentType(ContentType.JSON).body(body("week", 1))
                .when().put("/api/settings").then().statusCode(428);

        // The current version goes through.
        authed().contentType(ContentType.JSON).header("If-Match", etag1).body(body("day", 2))
                .when().put("/api/settings").then().statusCode(200).body("horizonUnit", is("day"));
    }
}
