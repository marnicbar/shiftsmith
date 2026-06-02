package dev.shiftsmith.support;

import ai.timefold.solver.core.api.score.stream.test.ConstraintVerifier;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import dev.shiftsmith.domain.Schedule;
import dev.shiftsmith.domain.ShiftAssignment;
import dev.shiftsmith.solver.ScheduleConstraintProvider;

import java.time.Duration;

/**
 * Builds the real solver stack (the production {@link ScheduleConstraintProvider})
 * for tests, without bootstrapping Quarkus or a database. This keeps solver and
 * constraint tests fast and runnable anywhere (no Docker required).
 *
 * <ul>
 *   <li>{@link #solve(Schedule)} — run the solver to a steady state. Termination is
 *       kept short because every test problem is intentionally tiny.</li>
 *   <li>{@link #constraintVerifier()} — Timefold's {@code ConstraintVerifier}, which
 *       drives a single constraint over a handful of facts so it can be asserted in
 *       isolation (penalty / reward / no-impact).</li>
 * </ul>
 *
 * The solver runs in {@code REPRODUCIBLE} mode (the default), so a given problem
 * always yields the same solution — a prerequisite for exact-match assertions.
 */
public final class SolverHarness {

    private SolverHarness() {}

    private static SolverConfig baseConfig() {
        return new SolverConfig()
                .withSolutionClass(Schedule.class)
                .withEntityClasses(ShiftAssignment.class)
                .withConstraintProviderClass(ScheduleConstraintProvider.class);
    }

    /** Solve to a steady state. Tiny test problems converge well within these limits. */
    public static Schedule solve(Schedule problem) {
        SolverConfig config = baseConfig()
                .withTerminationConfig(new TerminationConfig()
                        .withUnimprovedSpentLimit(Duration.ofSeconds(1))
                        .withSpentLimit(Duration.ofSeconds(10)));
        return SolverFactory.<Schedule>create(config).buildSolver().solve(problem);
    }

    /** Verifier for asserting a single constraint's impact over a fixed set of facts. */
    public static ConstraintVerifier<ScheduleConstraintProvider, Schedule> constraintVerifier() {
        return ConstraintVerifier.build(new ScheduleConstraintProvider(), Schedule.class, ShiftAssignment.class);
    }
}
