package dev.shiftsmith.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JUnit condition that enables a test only when a Docker daemon looks reachable.
 *
 * <p>{@code @QuarkusTest} integration tests rely on Quarkus Dev Services to spin up
 * a throwaway PostgreSQL container, which needs Docker. Gating those tests keeps the
 * pure unit/solver suite (the bulk of the coverage) runnable everywhere — including
 * CI agents and dev boxes without Docker — instead of failing to bootstrap.
 */
public class DockerAvailableCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (dockerAvailable()) {
            return ConditionEvaluationResult.enabled("Docker appears to be available");
        }
        return ConditionEvaluationResult.disabled(
                "Docker not available — skipping Quarkus integration test (needs Dev Services PostgreSQL)");
    }

    private static boolean dockerAvailable() {
        String host = System.getenv("DOCKER_HOST");
        if (host != null && !host.isBlank()) return true;
        return Files.exists(Path.of("/var/run/docker.sock"));
    }
}
