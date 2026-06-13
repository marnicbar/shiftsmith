package dev.shiftsmith.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.shiftsmith.auth.AuthService;
import dev.shiftsmith.domain.Block;
import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.support.EnabledIfDockerAvailable;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static dev.shiftsmith.support.Fixtures.employee;
import static dev.shiftsmith.support.Fixtures.window;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

/**
 * Phase 6 of issue #47: per-employee authorization. An {@code employee} account may
 * edit only its own calendar; it cannot touch another person's calendar or the
 * catalogue (employees/positions/settings). A manager/admin has full access. Gated on
 * a database.
 */
@QuarkusTest
@EnabledIfDockerAvailable
class PerEmployeeAuthorizationIT {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final LocalDate today = LocalDate.now();

    @Inject
    AuthService auth;

    private String tokenFor(String username, String password) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("username", username, "password", password, "remember", false))
                .when().post("/api/auth/login").then().statusCode(200).extract().path("token");
    }

    private io.restassured.specification.RequestSpecification as(String token) {
        return given().header("Authorization", "Bearer " + token);
    }

    private String availabilityJson() throws Exception {
        Block b = window("pref", today, 480, 1080); // 08:00–18:00
        return MAPPER.writeValueAsString(List.of(b));
    }

    /** Idempotent setup: an admin creates the person, then an employee account linked to it. */
    @BeforeEach
    void setup() throws Exception {
        String admin = tokenFor("admin", "shiftsmith");
        Employee alice = employee("auth-alice", "Bar");
        as(admin).contentType(ContentType.JSON).body(MAPPER.writeValueAsString(alice))
                .when().post("/api/employees"); // 201 first time, 409 after — either is fine
        auth.createUser("auth-emp", "secret123", "employee", "auth-alice"); // idempotent
    }

    @Test
    void employeeCanEditOnlyItsOwnCalendar() throws Exception {
        String emp = tokenFor("auth-emp", "secret123");

        // Its own availability + rules: allowed.
        as(emp).contentType(ContentType.JSON).body(availabilityJson())
                .when().put("/api/employees/auth-alice/availability").then().statusCode(200);
        as(emp).contentType(ContentType.JSON).body("[]")
                .when().put("/api/employees/auth-alice/rules").then().statusCode(200);

        // Another person's calendar: forbidden.
        as(emp).contentType(ContentType.JSON).body(availabilityJson())
                .when().put("/api/employees/auth-bob/availability").then().statusCode(403);
    }

    @Test
    void employeeCannotTouchTheCatalogue() throws Exception {
        String emp = tokenFor("auth-emp", "secret123");

        // The full employee write (scalar/skills) is manager-only, even for its own record.
        as(emp).contentType(ContentType.JSON).header("If-Match", "0")
                .body(MAPPER.writeValueAsString(employee("auth-alice", "Bar")))
                .when().put("/api/employees/auth-alice").then().statusCode(403);

        as(emp).contentType(ContentType.JSON)
                .body("{\"id\":\"x\",\"name\":\"X\",\"color\":1,\"skills\":[],\"shifts\":[]}")
                .when().post("/api/positions").then().statusCode(403);

        as(emp).contentType(ContentType.JSON).header("If-Match", "0")
                .body("{\"horizonUnit\":\"week\",\"horizonCount\":1,\"skills\":[],\"globalRules\":[]}")
                .when().put("/api/settings").then().statusCode(403);
    }

    @Test
    void managerHasFullAccessAndMeReportsTheRole() throws Exception {
        String admin = tokenFor("admin", "shiftsmith");
        as(admin).contentType(ContentType.JSON).body(availabilityJson())
                .when().put("/api/employees/auth-alice/availability").then().statusCode(200);

        // The employee account's /me reports its role + the person it represents.
        String emp = tokenFor("auth-emp", "secret123");
        as(emp).when().get("/api/auth/me")
                .then().statusCode(200)
                .body("role", is("employee"))
                .body("employeeId", is("auth-alice"));
    }
}
