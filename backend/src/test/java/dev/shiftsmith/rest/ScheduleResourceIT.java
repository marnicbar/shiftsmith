package dev.shiftsmith.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.Position;
import dev.shiftsmith.domain.ShiftTemplate;
import dev.shiftsmith.support.EnabledIfDockerAvailable;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

import static dev.shiftsmith.support.Fixtures.availableAllDay;
import static dev.shiftsmith.support.Fixtures.employee;
import static dev.shiftsmith.support.Fixtures.position;
import static dev.shiftsmith.support.Fixtures.template;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Full-stack smoke test of the REST + persistence + solver wiring. Boots the real
 * Quarkus app (with a Dev Services PostgreSQL), so it only runs when Docker is
 * available — see {@link EnabledIfDockerAvailable}.
 *
 * <p>It drives the contract the frontend uses: create the problem through the granular
 * write endpoints, then read it back from {@code GET /api/schedule} and confirm the
 * slots were expanded and the solver staffed them. Assertions are presence-based and
 * id/skill-namespaced, since the test database is shared across @QuarkusTest classes.
 */
@QuarkusTest
@EnabledIfDockerAvailable
class ScheduleResourceIT {

    /** Base URL of the running test server (injected so the test port isn't hard-coded). */
    @io.quarkus.test.common.http.TestHTTPResource
    java.net.URL baseUrl;

    @jakarta.inject.Inject
    dev.shiftsmith.persistence.AssignmentStore assignmentStore;

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

    @Test
    void problemRoundTripsAndIsSolvedToFullCoverage() throws Exception {
        // A shift needing a one-off skill only this employee has, so the assignment is
        // deterministic even with other ITs' data in the shared database.
        Employee alice = availableAllDay(employee("sr-alice", "SrOnly"), today);
        alice.getBlocks().get(0).setId("blk-sr-alice");
        authed().contentType(ContentType.JSON).body(MAPPER.writeValueAsString(alice))
                .when().post("/api/employees").then().statusCode(201);

        Position p = position("sr-p", "Bar");
        p.getShifts().add(template("sr-bar", today, 1020, 1440, 1, "SrOnly")); // 17:00–24:00 today
        authed().contentType(ContentType.JSON).body(MAPPER.writeValueAsString(p))
                .when().post("/api/positions").then().statusCode(201);

        // It comes back with the persisted employee/position and the expanded slot.
        authed().when().get("/api/schedule")
                .then().statusCode(200)
                .body("employees.id", hasItem("sr-alice"))
                .body("positions.id", hasItem("sr-p"))
                .body("horizonStart", notNullValue())
                .body("solverStatus", notNullValue());

        // The only qualified employee should get staffed on that slot.
        String slot = "sr-bar@" + today + "#0";
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250)).untilAsserted(() ->
                authed().when().get("/api/schedule")
                        .then().statusCode(200)
                        .body("assignments.findAll { it.id == '" + slot + "' }.employeeId", hasItem("sr-alice")));
    }

    /**
     * Once the solver settles, its picks are persisted as {@code assignment} rows
     * (issue #47, Phase 2) so the roster survives a restart. Drive the same trivial
     * problem, wait for the final best solution, and assert the solved slot was
     * written to the durable store keyed by its expanded slot id.
     */
    @Test
    void solvedScheduleIsPersistedAsAssignmentRows() throws Exception {
        Employee al = availableAllDay(employee("sr2-alice", "Sr2Only"), today);
        al.getBlocks().get(0).setId("blk-sr2-alice");
        authed().contentType(ContentType.JSON).body(MAPPER.writeValueAsString(al))
                .when().post("/api/employees").then().statusCode(201);
        Position p = position("sr2-p", "Bar");
        p.getShifts().add(template("sr2-bar", today, 1020, 1440, 1, "Sr2Only"));
        authed().contentType(ContentType.JSON).body(MAPPER.writeValueAsString(p))
                .when().post("/api/positions").then().statusCode(201);

        // The window spans at least today, so the solver staffs the slot and persists it.
        LocalDate from = today;
        LocalDate to = today.plusDays(2);
        await().atMost(Duration.ofSeconds(25)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            Map<String, String> persisted = assignmentStore.loadAssignedEmployees(from, to);
            org.assertj.core.api.Assertions.assertThat(persisted)
                    .containsEntry("sr2-bar@" + today + "#0", "sr2-alice");
        });
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
     * browser's EventSource never received a frame. The endpoint is {@code @Blocking};
     * assert it opens (200, {@code text/event-stream}) and pushes the initial typed
     * change event (issue #47, Phase 5: deltas, not full snapshots).
     */
    @Test
    void streamOpensAndPushesAnInitialEvent() throws Exception {
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
        org.assertj.core.api.Assertions.assertThat(firstFrame).contains("\"type\":\"connected\"");
    }

    /**
     * Phase 5: a granular edit emits a typed change event naming the affected resource,
     * so another client refetches only that slice instead of the whole snapshot.
     */
    @Test
    void streamEmitsATypedEventForAGranularEdit() throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.URI streamUri = baseUrl.toURI().resolve("/api/stream?token=" + token());
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(streamUri).header("Accept", "text/event-stream").GET().build();
        java.net.http.HttpResponse<java.util.stream.Stream<String>> res =
                client.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofLines())
                        .get(15, java.util.concurrent.TimeUnit.SECONDS);
        org.assertj.core.api.Assertions.assertThat(res.statusCode()).isEqualTo(200);

        java.util.List<String> frames = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Thread reader = new Thread(() -> res.body().forEach(frames::add));
        reader.setDaemon(true);
        reader.start();
        Thread.sleep(400); // let the subscription attach

        Employee e = availableAllDay(employee("sse-emp", "Bar"), today);
        e.getBlocks().get(0).setId("blk-sse-emp");
        authed().contentType(ContentType.JSON).body(MAPPER.writeValueAsString(e))
                .when().post("/api/employees").then().statusCode(201);

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                org.assertj.core.api.Assertions.assertThat(frames.stream()
                        .anyMatch(l -> l.contains("\"type\":\"employee\"") && l.contains("sse-emp"))).isTrue());
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
