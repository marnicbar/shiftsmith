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

    /** Base URL of the running test server (injected so the test port isn't hard-coded). */
    @io.quarkus.test.common.http.TestHTTPResource
    java.net.URL baseUrl;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** Anchor the template to the real "today" so it lands inside the live solve window. */
    private final LocalDate today = LocalDate.now();

    /** Log in with the seeded default account and return the raw session token. */
    private String token() {
        return given().contentType(ContentType.JSON)
                .body(Map.of("username", "admin", "password", "shiftsmith", "remember", false))
                .when().post("/api/auth/login")
                .then().statusCode(200).extract().path("token");
    }

    /** Log in with the seeded default account and return a request spec carrying the token. */
    private io.restassured.specification.RequestSpecification authed() {
        return given().header("Authorization", "Bearer " + token());
    }

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
        authed().contentType(ContentType.JSON).body(trivialProblemJson())
                .when().put("/api/problem")
                .then().statusCode(204);

        // It comes back with the expanded slot and the persisted employee/position.
        authed().when().get("/api/schedule")
                .then().statusCode(200)
                .body("employees.size()", is(1))
                .body("positions.size()", is(1))
                .body("total", is(1))
                .body("horizonStart", notNullValue())
                .body("solverStatus", notNullValue());

        // The single feasible slot should get staffed by the only qualified employee.
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250)).untilAsserted(() ->
                authed().when().get("/api/schedule")
                        .then().statusCode(200)
                        .body("staffed", is(1))
                        .body("assignments[0].employeeId", is("alice")));
    }

    @Test
    void solverLifecycleEndpointsAreReachable() {
        // The resource consumes JSON; declare it so RestAssured doesn't default to a
        // form content type on these bodyless calls (which would be rejected as 415).
        authed().contentType(ContentType.JSON).when().post("/api/solve").then().statusCode(204);
        authed().contentType(ContentType.JSON).when().delete("/api/solve").then().statusCode(204);
    }

    @Test
    void apiRequiresAuthentication() {
        given().when().get("/api/schedule").then().statusCode(401);
    }

    /**
     * Regression test for the live-update SSE stream. The shared auth filter does a
     * transactional DB lookup (the seeded-password check); on the {@code Multi}-
     * returning stream endpoint that filter ran on the reactive IO thread, where a
     * blocking JTA transaction is illegal, so the stream answered 500 and the
     * browser's EventSource never received a frame — the UI only refreshed on a
     * manual reload. The endpoint is now {@code @Blocking}; assert it actually opens
     * (200, {@code text/event-stream}) and pushes at least the initial snapshot.
     */
    @Test
    void streamOpensAndPushesAnInitialSnapshot() throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.URI streamUri = baseUrl.toURI().resolve("/api/stream?token=" + token());
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(streamUri)
                .header("Accept", "text/event-stream")
                .GET().build();

        java.net.http.HttpResponse<java.util.stream.Stream<String>> res =
                client.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofLines())
                        .get(15, java.util.concurrent.TimeUnit.SECONDS);

        org.assertj.core.api.Assertions.assertThat(res.statusCode()).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThat(res.headers().firstValue("content-type").orElse(""))
                .contains("text/event-stream");

        // The stream is endless, so read just the first data frame on a bounded wait.
        String firstFrame = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                        res.body().filter(l -> l.startsWith("data:")).findFirst().orElse(""))
                .get(15, java.util.concurrent.TimeUnit.SECONDS);
        org.assertj.core.api.Assertions.assertThat(firstFrame).contains("solverStatus");
    }

    @Test
    void meReportsTheForcedPasswordChangeFlag() {
        // The test profile provisions the admin password, so the seeded account is
        // operator-chosen and not gated behind a forced password change.
        authed().when().get("/api/auth/me")
                .then().statusCode(200)
                .body("username", is("admin"))
                .body("mustChangePassword", is(false));
    }
}
