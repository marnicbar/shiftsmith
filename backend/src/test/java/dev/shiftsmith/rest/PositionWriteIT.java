package dev.shiftsmith.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.shiftsmith.domain.Position;
import dev.shiftsmith.domain.ShiftTemplate;
import dev.shiftsmith.persistence.entity.AssignmentEntity;
import dev.shiftsmith.support.EnabledIfDockerAvailable;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static dev.shiftsmith.support.Fixtures.position;
import static dev.shiftsmith.support.Fixtures.template;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Phase 4 of issue #47: granular, concurrency-safe position writes — including that an
 * update upserts the position's templates by id, so a template's durable assignment
 * history survives the edit (only a dropped template is pruned, #39). Gated on a database.
 */
@QuarkusTest
@EnabledIfDockerAvailable
class PositionWriteIT {

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

    /** A position carrying one template, both id-namespaced to this suite. */
    private String json(String id, String name, String templateId) throws Exception {
        Position p = position(id, name);
        ShiftTemplate t = template(templateId, today, 540, 1020, 1, "Bar");
        p.getShifts().add(t);
        return MAPPER.writeValueAsString(p);
    }

    private String create(String id, String name, String templateId) throws Exception {
        return authed().contentType(ContentType.JSON).body(json(id, name, templateId))
                .when().post("/api/positions").then().statusCode(201).extract().header("ETag");
    }

    @Test
    void createUpdateDeleteWithOptimisticConcurrency() throws Exception {
        String etag0 = create("pw-bar", "Bar", "pw-t1");
        String getEtag = authed().when().get("/api/positions/pw-bar")
                .then().statusCode(200).body("name", is("Bar")).extract().header("ETag");
        assertThat(getEtag).isEqualTo(etag0);

        String etag1 = authed().contentType(ContentType.JSON).header("If-Match", etag0).body(json("pw-bar", "Lounge", "pw-t1"))
                .when().put("/api/positions/pw-bar").then().statusCode(200).body("name", is("Lounge")).extract().header("ETag");
        assertThat(etag1).isNotEqualTo(etag0);

        // Stale write refused; current one succeeds.
        authed().contentType(ContentType.JSON).header("If-Match", etag0).body(json("pw-bar", "X", "pw-t1"))
                .when().put("/api/positions/pw-bar").then().statusCode(409);
        authed().contentType(ContentType.JSON).body(json("pw-bar", "X", "pw-t1"))
                .when().put("/api/positions/pw-bar").then().statusCode(428); // If-Match required

        authed().header("If-Match", etag1).when().delete("/api/positions/pw-bar").then().statusCode(204);
        authed().when().get("/api/positions/pw-bar").then().statusCode(404);
    }

    @Test
    void updatePreservesTemplateAssignmentHistoryButDeletePrunesIt() throws Exception {
        String etag0 = create("pw-hist", "Hist", "pw-th");
        seedPastAssignment("pw-th");
        assertThat(pastRowsFor("pw-th")).isEqualTo(1);

        // Updating the position (keeping the template) must not drop the template's history.
        authed().contentType(ContentType.JSON).header("If-Match", etag0).body(json("pw-hist", "Hist2", "pw-th"))
                .when().put("/api/positions/pw-hist").then().statusCode(200);
        assertThat(pastRowsFor("pw-th")).isEqualTo(1);

        // Deleting the position cascade-prunes the template and its assignment rows (#39).
        String etag1 = authed().when().get("/api/positions/pw-hist").then().statusCode(200).extract().header("ETag");
        authed().header("If-Match", etag1).when().delete("/api/positions/pw-hist").then().statusCode(204);
        assertThat(pastRowsFor("pw-th")).isZero();
    }

    private void seedPastAssignment(String templateId) {
        LocalDate past = today.minusDays(20);
        QuarkusTransaction.requiringNew().run(() -> {
            AssignmentEntity ae = new AssignmentEntity();
            ae.templateId = templateId;
            ae.occurrenceDate = past;
            ae.slotIndex = 0;
            ae.startTs = past.atTime(9, 0);
            ae.endTs = past.atTime(17, 0);
            ae.pinned = false;
            ae.source = "solver";
            ae.persist();
        });
    }

    /** Count only the seeded history row, not any window row the solver may persist for today. */
    private long pastRowsFor(String templateId) {
        return QuarkusTransaction.requiringNew().call(
                () -> AssignmentEntity.count("templateId = ?1 and occurrenceDate < ?2", templateId, today));
    }
}
