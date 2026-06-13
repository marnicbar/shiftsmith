package dev.shiftsmith.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.support.EnabledIfDockerAvailable;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static dev.shiftsmith.support.Fixtures.availableAllDay;
import static dev.shiftsmith.support.Fixtures.employee;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

/**
 * Phase 4 of issue #47: granular, concurrency-safe employee writes. Two clients
 * editing the same person can't silently clobber each other (a stale version is a
 * 409), while edits to different people never conflict. Gated on a database.
 */
@QuarkusTest
@EnabledIfDockerAvailable
class EmployeeWriteIT {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final LocalDate today = LocalDate.now();

    private String token() {
        return given().contentType(ContentType.JSON)
                .body(Map.of("username", "admin", "password", "shiftsmith", "remember", false))
                .when().post("/api/auth/login").then().statusCode(200).extract().path("token");
    }

    private io.restassured.specification.RequestSpecification authed() {
        return given().header("Authorization", "Bearer " + token());
    }

    private String json(String id, String firstName) throws Exception {
        Employee e = availableAllDay(employee(id, "Bar"), today);
        e.getBlocks().get(0).setId("blk-" + id);
        e.setFirstName(firstName);
        return MAPPER.writeValueAsString(e);
    }

    private String create(String id, String firstName) throws Exception {
        return authed().contentType(ContentType.JSON).body(json(id, firstName))
                .when().post("/api/employees")
                .then().statusCode(201).extract().header("ETag");
    }

    @Test
    void createReadUpdateDeleteRoundTrip() throws Exception {
        String etag0 = create("ew-alice", "Alice");

        // The created version is also what GET reports as the ETag.
        String getEtag = authed().when().get("/api/employees/ew-alice")
                .then().statusCode(200).body("firstName", is("Alice")).extract().header("ETag");
        org.assertj.core.api.Assertions.assertThat(getEtag).isEqualTo(etag0);

        // A matching If-Match write succeeds and bumps the version.
        String etag1 = authed().contentType(ContentType.JSON).header("If-Match", etag0).body(json("ew-alice", "Alicia"))
                .when().put("/api/employees/ew-alice")
                .then().statusCode(200).body("firstName", is("Alicia")).extract().header("ETag");
        org.assertj.core.api.Assertions.assertThat(etag1).isNotEqualTo(etag0);

        // Delete needs the current version; then it's gone.
        authed().header("If-Match", etag1).when().delete("/api/employees/ew-alice").then().statusCode(204);
        authed().when().get("/api/employees/ew-alice").then().statusCode(404);
    }

    @Test
    void staleVersionWriteIsRejectedWithoutOverwriting() throws Exception {
        String etag0 = create("ew-bob", "Bob");

        // Two clients both hold the same (v0) ETag.
        // Client A wins.
        authed().contentType(ContentType.JSON).header("If-Match", etag0).body(json("ew-bob", "Bobby"))
                .when().put("/api/employees/ew-bob").then().statusCode(200);

        // Client B, still on the stale v0, is refused — its edit did NOT overwrite A's.
        authed().contentType(ContentType.JSON).header("If-Match", etag0).body(json("ew-bob", "Robert"))
                .when().put("/api/employees/ew-bob").then().statusCode(409);

        authed().when().get("/api/employees/ew-bob").then().statusCode(200).body("firstName", is("Bobby"));
    }

    @Test
    void writesToDifferentEmployeesNeverConflict() throws Exception {
        String aTag = create("ew-carol", "Carol");
        String bTag = create("ew-dave", "Dave");

        // Independent edits, each with its own current version, both succeed.
        authed().contentType(ContentType.JSON).header("If-Match", aTag).body(json("ew-carol", "Caro"))
                .when().put("/api/employees/ew-carol").then().statusCode(200);
        authed().contentType(ContentType.JSON).header("If-Match", bTag).body(json("ew-dave", "David"))
                .when().put("/api/employees/ew-dave").then().statusCode(200);
    }

    @Test
    void mutatingWritesRequireAnIfMatch() throws Exception {
        create("ew-erin", "Erin");
        authed().contentType(ContentType.JSON).body(json("ew-erin", "Erin2"))
                .when().put("/api/employees/ew-erin").then().statusCode(428);
        authed().when().delete("/api/employees/ew-erin").then().statusCode(428);
    }
}
