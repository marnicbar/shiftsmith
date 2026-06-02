package dev.shiftsmith.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static dev.shiftsmith.support.Fixtures.change;
import static dev.shiftsmith.support.Fixtures.rule;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Rule#effectiveAt} resolves a rule as it stands on a given date, applying
 * scheduled {@link Change}s. These tests cover the time-travel behaviour the
 * hour-limit constraints depend on.
 */
class RuleEffectiveAtTest {

    private static final LocalDate JUN1 = LocalDate.of(2026, 6, 1);

    @Test
    void withoutChangesTheRuleIsAlwaysActiveWithItsBaseValue() {
        Rule r = rule("weekHours", "max", 40);
        Rule.Effective e = r.effectiveAt(JUN1);
        assertThat(e.active()).isTrue();
        assertThat(e.metric()).isEqualTo("weekHours");
        assertThat(e.op()).isEqualTo("max");
        assertThat(e.value()).isEqualTo(40);
    }

    @Test
    void setChangeAppliesOnlyOnOrAfterItsDate() {
        Rule r = rule("weekHours", "preferred", 38);
        r.getChanges().add(change(JUN1.plusDays(10), "set", "weekHours", "preferred", 30));

        assertThat(r.effectiveAt(JUN1.plusDays(9)).value()).isEqualTo(38);   // before change
        assertThat(r.effectiveAt(JUN1.plusDays(10)).value()).isEqualTo(30);  // on change date
        assertThat(r.effectiveAt(JUN1.plusDays(20)).value()).isEqualTo(30);  // after
    }

    @Test
    void latestApplicableChangeWins() {
        Rule r = rule("weekHours", "preferred", 38);
        r.getChanges().add(change(JUN1.plusDays(5), "set", null, null, 30));
        r.getChanges().add(change(JUN1.plusDays(15), "set", null, null, 20));
        assertThat(r.effectiveAt(JUN1.plusDays(10)).value()).isEqualTo(30);
        assertThat(r.effectiveAt(JUN1.plusDays(20)).value()).isEqualTo(20);
    }

    @Test
    void changesApplyOutOfOrderByDateNotListOrder() {
        Rule r = rule("weekHours", "preferred", 38);
        // deliberately add the later change first
        r.getChanges().add(change(JUN1.plusDays(15), "set", null, null, 20));
        r.getChanges().add(change(JUN1.plusDays(5), "set", null, null, 30));
        assertThat(r.effectiveAt(JUN1.plusDays(20)).value()).isEqualTo(20);
    }

    @Test
    void removeChangeDeactivatesTheRule() {
        Rule r = rule("dayHours", "max", 10);
        r.getChanges().add(change(JUN1.plusDays(7), "remove", null, null, 0));
        assertThat(r.effectiveAt(JUN1.plusDays(6)).active()).isTrue();
        assertThat(r.effectiveAt(JUN1.plusDays(7)).active()).isFalse();
    }

    @Test
    void removeThenSetReactivatesWithNewValue() {
        Rule r = rule("dayHours", "max", 10);
        r.getChanges().add(change(JUN1.plusDays(7), "remove", null, null, 0));
        r.getChanges().add(change(JUN1.plusDays(14), "set", "dayHours", "max", 8));
        assertThat(r.effectiveAt(JUN1.plusDays(10)).active()).isFalse();
        Rule.Effective after = r.effectiveAt(JUN1.plusDays(14));
        assertThat(after.active()).isTrue();
        assertThat(after.value()).isEqualTo(8);
    }
}
