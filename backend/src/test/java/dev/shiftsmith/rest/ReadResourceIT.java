package dev.shiftsmith.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.Position;
import dev.shiftsmith.persistence.entity.AssignmentEntity;
import dev.shiftsmith.support.EnabledIfDockerAvailable;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static dev.shiftsmith.support.Fixtures.availableAllDay;
import static dev.shiftsmith.support.Fixtures.employee;
import static dev.shiftsmith.support.Fixtures.position;
import static dev.shiftsmith.support.Fixtures.rule;
import static dev.shiftsmith.support.Fixtures.template;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

/**
 * Phase 3 of issue #47: the granular, windowed read endpoints. Boots the real stack
 * against PostgreSQL, so it is gated on a database. The data is seeded through the
 * granular write endpoints and asserted by presence (ids are namespaced) since the
 * database is shared across @QuarkusTest classes.
 */
@QuarkusTest
@EnabledIfDockerAvailable
class ReadResourceIT {

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

    /** Seed (idempotently) a two-person, one-position problem through the granular writes. */
    @BeforeEach
    void seedProblem() throws Exception {
        String settingsEtag = authed().when().get("/api/settings").then().statusCode(200).extract().header("ETag");
        authed().contentType(ContentType.JSON).header("If-Match", settingsEtag)
                .body("{\"horizonUnit\":\"day\",\"horizonCount\":1,\"skills\":[\"Bar\",\"Kitchen\"],\"globalRules\":[]}")
                .when().put("/api/settings").then().statusCode(200);

        Employee alice = availableAllDay(employee("rr-alice", "Bar"), today);
        alice.getBlocks().get(0).setId("blk-rr-alice");
        alice.getRules().add(rule("weekHours", "max", 40));
        authed().contentType(ContentType.JSON).body(MAPPER.writeValueAsString(alice))
                .when().post("/api/employees"); // 201 first time, 409 after — both fine

        Employee bob = availableAllDay(employee("rr-bob", "Kitchen"), today);
        bob.getBlocks().get(0).setId("blk-rr-bob");
        authed().contentType(ContentType.JSON).body(MAPPER.writeValueAsString(bob))
                .when().post("/api/employees");

        Position p = position("rr-p1", "Bar");
        p.getShifts().add(template("rr-t1", today, 1020, 1440, 1, "Bar"));
        authed().contentType(ContentType.JSON).body(MAPPER.writeValueAsString(p))
                .when().post("/api/positions");
    }

    @Test
    void settingsAndSkills() {
        authed().when().get("/api/settings").then().statusCode(200).body("horizonUnit", is("day"));
        authed().when().get("/api/skills").then().statusCode(200).body("$", hasItem("Bar"));
    }

    @Test
    void employeesArePagedAndFetchableById() {
        authed().when().get("/api/employees?page=0&size=1")
                .then().statusCode(200)
                .body("total", greaterThanOrEqualTo(2))
                .body("size", is(1))
                .body("items.size()", is(1));

        authed().when().get("/api/employees/rr-alice").then().statusCode(200).body("id", is("rr-alice"));
        authed().when().get("/api/employees/nobody").then().statusCode(404);
    }

    @Test
    void employeeAvailabilityAndRules() {
        authed().when().get("/api/employees/rr-alice/availability?from=" + today + "&to=" + today)
                .then().statusCode(200).body("size()", is(1)); // the all-day pref block

        authed().when().get("/api/employees/rr-alice/rules")
                .then().statusCode(200).body("[0].metric", is("weekHours"));

        // Missing range params are a 400.
        authed().when().get("/api/employees/rr-alice/availability").then().statusCode(400);
    }

    @Test
    void positionsAndTheirTemplates() {
        authed().when().get("/api/positions?page=0&size=10").then().statusCode(200)
                .body("total", greaterThanOrEqualTo(1));
        authed().when().get("/api/positions/rr-p1").then().statusCode(200).body("name", is("Bar"));
        authed().when().get("/api/positions/rr-p1/shift-templates")
                .then().statusCode(200).body("[0].id", is("rr-t1"));
    }

    @Test
    void scheduleRangeSpansHistoryAndFiltersByScope() {
        // Seed a worked shift 10 days ago — outside the live window, only reachable via the range read.
        LocalDate past = today.minusDays(10);
        QuarkusTransaction.requiringNew().run(() -> {
            AssignmentEntity ae = new AssignmentEntity();
            ae.templateId = "rr-t1";
            ae.occurrenceDate = past;
            ae.slotIndex = 0;
            ae.startTs = past.atTime(17, 0);
            ae.endTs = past.plusDays(1).atStartOfDay();
            ae.employeeId = "rr-alice";
            ae.pinned = false;
            ae.source = "solver";
            ae.persist();
        });

        String from = today.minusDays(15).toString();
        String to = today.plusDays(1).toString();
        String slot = "rr-t1@" + past + "#0";

        authed().when().get("/api/schedule/range?from=" + from + "&to=" + to)
                .then().statusCode(200)
                .body("findAll { it.id == '" + slot + "' }.employeeId", hasItem("rr-alice"))
                .body("findAll { it.id == '" + slot + "' }.positionId", hasItem("rr-p1"));

        // person scope keeps alice's slot, position scope keeps p1's slot...
        authed().when().get("/api/schedule/range?from=" + from + "&to=" + to + "&scope=person:rr-alice")
                .then().statusCode(200).body("id", hasItem(slot));
        authed().when().get("/api/schedule/range?from=" + from + "&to=" + to + "&scope=position:rr-p1")
                .then().statusCode(200).body("id", hasItem(slot));
        // ...but a different person sees nothing of it.
        authed().when().get("/api/schedule/range?from=" + from + "&to=" + to + "&scope=person:rr-bob")
                .then().statusCode(200).body("findAll { it.id == '" + slot + "' }.size()", is(0));

        authed().when().get("/api/schedule/range").then().statusCode(400); // range required
    }
}
