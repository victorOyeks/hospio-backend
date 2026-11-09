package com.hospio.finance.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The night audit's checklist: each item is done only when nothing is left hanging. */
class NightAuditChecklistTest {

    @Test
    void aCleanDayHasEveryItemDone() {
        List<NightAuditChecklist.Item> items = NightAuditChecklist.build(0, 0, List.of(), 0, BigDecimal.ZERO);

        assertThat(items).extracting(NightAuditChecklist.Item::key).containsExactly("arrivals", "departures", "tills", "balances");
        assertThat(items).allMatch(NightAuditChecklist.Item::done);
    }

    @Test
    void eachRuleFailsOnItsOwn() {
        List<NightAuditChecklist.Item> items = NightAuditChecklist.build(2, 1, List.of("front office", "bar"), 3, new BigDecimal("84000.00"));

        assertThat(NightAuditChecklist.done(items, "arrivals")).isFalse();
        assertThat(NightAuditChecklist.done(items, "departures")).isFalse();
        assertThat(NightAuditChecklist.done(items, "tills")).isFalse();
        assertThat(NightAuditChecklist.done(items, "balances")).isFalse();
        assertThat(items.get(0).detail()).contains("2 guests");
        assertThat(items.get(2).detail()).contains("front office, bar");
        assertThat(items.get(3).detail()).contains("84000.00");
    }

    @Test
    void onlyTheFailingItemIsNotDone() {
        List<NightAuditChecklist.Item> items = NightAuditChecklist.build(0, 0, List.of("bar"), 0, BigDecimal.ZERO);

        assertThat(NightAuditChecklist.done(items, "arrivals")).isTrue();
        assertThat(NightAuditChecklist.done(items, "tills")).isFalse();
        assertThat(items.get(2).detail()).startsWith("1 till still open");
    }
}
