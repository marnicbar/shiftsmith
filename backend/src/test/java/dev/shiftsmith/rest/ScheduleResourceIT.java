package dev.shiftsmith.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.Position;
import dev.shiftsmith.domain.Settings;
import dev.shiftsmith.domain.ShiftTemplate;
import dev.shiftsmith.rest.dto.ProblemDTO;
import dev.shiftsmith.support.EnabledIfDockerAvailable;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static dev.shiftsmith.support.Fixtures.availableAllDay;
import static dev.shiftsmith.support.Fixtures.employee;
import static dev.shiftsmith.support.Fixtures.position;
import static dev.shiftsmith.support.Fixtures.template;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Full-stack smoke test of the REST + persistence + solver wiring. Boots the real
 * Quarkus app (with a Dev Services PostgreSQL), so it only runs when Docker is
 * available — see {@link EnabledIfDockerAvailable}.
 *
 * <p>It drives the same contract the frontend uses: push the whole problem to
 * {@code PUT /api/problem}, then read it back from {@code GET /api/schedule} and
 * confirm the slots were expanded and the solver staffed them.
 */
@QuarkusTest
@EnabledIfDockerAvailable
class ScheduleResourceIT {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** Anchor the template to the real "today" so it lands inside the live solve window. */
    private final LocalDate today = LocalDate.now();

    private String trivialProblemJson() throws Exception {
        ShiftTemplate bar = template("bar", today, 1020, 1440, 1, "Bar"); // 17:00–24:00 today
        Position p = position("p", "Bar");
        p.getShifts().add(bar);
        Employee alice = availableAllDay(employee("alice", "Bar"), today);

        ProblemDTO dto = new ProblemDTO();
        dto.employees = List.of(alice);
        dto.positions = List.of(p);
        dto.settings = new Settings("day", 1); // window = today + tomorrow
        dto.overrides = Map.of();
        return MAPPER.writeValueAsString(dto);
    }

    @Test
    void problemRoundTripsAndIsSolvedToFullCoverage() throws Exception {
        // Push the problem.
        given().contentType(ContentType.JSON).body(trivialProblemJson())
                .when().put("/api/problem")
                .then().statusCode(204);

        // It comes back with the expanded slot and the persisted employee/position.
        given().when().get("/api/schedule")
                .then().statusCode(200)
                .body("employees.size()", is(1))
                .body("positions.size()", is(1))
                .body("total", is(1))
                .body("horizonStart", notNullValue())
                .body("solverStatus", notNullValue());

        // The single feasible slot should get staffed by the only qualified employee.
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250)).untilAsserted(() ->
                given().when().get("/api/schedule")
                        .then().statusCode(200)
                        .body("staffed", is(1))
                        .body("assignments[0].employeeId", is("alice")));
    }

    @Test
    void solverLifecycleEndpointsAreReachable() {
        // The resource consumes JSON; declare it so RestAssured doesn't default to a
        // form content type on these bodyless calls (which would be rejected as 415).
        given().contentType(ContentType.JSON).when().post("/api/solve").then().statusCode(204);
        given().contentType(ContentType.JSON).when().delete("/api/solve").then().statusCode(204);
    }
}
