package dev.shiftsmith.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.Position;
import dev.shiftsmith.domain.ShiftTemplate;
import dev.shiftsmith.persistence.entity.AssignmentEntity;
import dev.shiftsmith.support.EnabledIfDockerAvailable;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static dev.shiftsmith.support.Fixtures.availableAllDay;
import static dev.shiftsmith.support.Fixtures.employee;
import static dev.shiftsmith.support.Fixtures.position;
import static dev.shiftsmith.support.Fixtures.template;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;

/**
 * Phase 4 of issue #47: pin/unpin a shift occurrence through the granular assignment
 * endpoint (no longer riding on the bulk problem sync). Gated on a database.
 */
@QuarkusTest
@EnabledIfDockerAvailable
class AssignmentPinIT {

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

    private void seed() throws Exception {
        Employee alice = availableAllDay(employee("ap-alice", "Bar"), today);
        alice.getBlocks().get(0).setId("blk-ap-alice");
        authed().contentType(ContentType.JSON).body(MAPPER.writeValueAsString(alice))
                .when().post("/api/employees").then().statusCode(201);

        Position p = position("ap-p1", "Bar");
        ShiftTemplate t = template("ap-t1", today, 1020, 1440, 2, "Bar"); // headcount 2
        p.getShifts().add(t);
        authed().contentType(ContentType.JSON).body(MAPPER.writeValueAsString(p))
                .when().post("/api/positions").then().statusCode(201);
    }

    @Test
    void pinThenUnpinAnOccurrence() throws Exception {
        seed();

        // Pin slot 0 to alice; slot 1 stays pinned-but-empty.
        authed().contentType(ContentType.JSON).body(List.of("ap-alice"))
                .when().put("/api/assignments/ap-t1/" + today).then().statusCode(204);

        assertThat(pins("ap-t1")).containsExactlyInAnyOrder("0=ap-alice", "1=null");

        // It shows up in the windowed read as a pinned slot.
        authed().when().get("/api/schedule/range?from=" + today + "&to=" + today.plusDays(1) + "&scope=position:ap-p1")
                .then().statusCode(200)
                .body("findAll { it.id == 'ap-t1@" + today + "#0' }.employeeId", hasItem("ap-alice"));

        // Unpin removes the manual rows again.
        authed().when().delete("/api/assignments/ap-t1/" + today).then().statusCode(204);
        assertThat(pins("ap-t1")).isEmpty();
    }

    @Test
    void pinningAnUnknownTemplateIs404() {
        authed().contentType(ContentType.JSON).body(List.of("ap-alice"))
                .when().put("/api/assignments/no-such-template/" + today).then().statusCode(404);
    }

    /** The manual ("source=manual") rows of an occurrence as "slotIndex=employeeId" strings. */
    private List<String> pins(String templateId) {
        return QuarkusTransaction.requiringNew().call(() ->
                AssignmentEntity.<AssignmentEntity>list("templateId = ?1 and source = ?2", templateId, "manual")
                        .stream().map(a -> a.slotIndex + "=" + a.employeeId).toList());
    }
}
