package dev.shiftsmith.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static dev.shiftsmith.support.Fixtures.rule;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Global working-time rules (from Settings) apply to everyone as defaults. They
 * surface through {@link Employee#limit} only where the employee has no personal
 * rule for the same metric+op, and a personal rule — which the UI keeps stricter —
 * always wins.
 */
class EmployeeGlobalRulesTest {

    private static final LocalDate JUN1 = LocalDate.of(2026, 6, 1);

    private static Employee withRules(List<Rule> personal, List<Rule> global) {
        Employee e = new Employee();
        e.setId("e1");
        e.setRules(personal);
        e.setGlobalRules(global);
        return e;
    }

    @Test
    void globalRuleAppliesWhenThePersonHasNoPersonalRule() {
        Employee e = withRules(List.of(), List.of(rule("dayHours", "max", 10)));
        assertThat(e.maxLimit("dayHours", JUN1)).isEqualTo(10);
    }

    @Test
    void personalRuleOverridesTheGlobalOne() {
        Employee e = withRules(
                List.of(rule("dayHours", "max", 8)),
                List.of(rule("dayHours", "max", 10)));
        assertThat(e.maxLimit("dayHours", JUN1)).isEqualTo(8); // stricter personal ceiling wins
    }

    @Test
    void personalPreferredOverridesGlobalPreferredFreely() {
        Employee e = withRules(
                List.of(rule("weekHours", "preferred", 50)),
                List.of(rule("weekHours", "preferred", 30)));
        assertThat(e.preferred("weekHours", JUN1)).isEqualTo(50); // a preference can be anything
    }

    @Test
    void noRuleAtAllYieldsNull() {
        Employee e = withRules(List.of(), List.of());
        assertThat(e.maxLimit("weekHours", JUN1)).isNull();
    }

    @Test
    void differentMetricsResolveIndependently() {
        Employee e = withRules(
                List.of(rule("dayHours", "max", 8)),
                List.of(rule("dayHours", "max", 10), rule("weekHours", "max", 40)));
        assertThat(e.maxLimit("dayHours", JUN1)).isEqualTo(8);   // personal
        assertThat(e.maxLimit("weekHours", JUN1)).isEqualTo(40); // inherited global
    }
}
