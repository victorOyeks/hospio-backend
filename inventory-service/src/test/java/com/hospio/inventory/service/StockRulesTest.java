package com.hospio.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Approval routing, weighted cost, count posting, reorder suggestion and the once-only low alert (docs/inventory-stores.md · Rules). */
class StockRulesTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void ordersOverTheLimitGoToTheManagerFromTheStoreAndToTheOwnerFromAManager() {
        BigDecimal limit = d("200000");
        assertThat(StockRules.approverFor(d("200000"), limit, StockRules.Caller.STAFF)).isNull();
        assertThat(StockRules.approverFor(d("200000.01"), limit, StockRules.Caller.STAFF)).isEqualTo("MANAGER");
        assertThat(StockRules.approverFor(d("250000"), limit, StockRules.Caller.MANAGER)).isEqualTo("OWNER");
        assertThat(StockRules.approverFor(d("9000000"), limit, StockRules.Caller.OWNER)).isNull();
    }

    @Test
    void costIsTheWeightedAverageOfWhatIsHeldAndWhatArrived() {
        assertThat(StockRules.weightedCost(d("10"), d("1000"), d("10"), d("1500"))).isEqualByComparingTo("1250.00");
        assertThat(StockRules.weightedCost(d("0"), d("1000"), d("5"), d("1500"))).isEqualByComparingTo("1500.00");
        assertThat(StockRules.weightedCost(d("3"), null, d("5"), d("800"))).isEqualByComparingTo("800.00");
        assertThat(StockRules.weightedCost(d("1"), d("100"), d("2"), d("200"))).isEqualByComparingTo("166.67");
    }

    @Test
    void anApprovedCountKeepsSalesSinceTheCountAndNeverGoesBelowZero() {
        assertThat(StockRules.countDelta(d("8"), d("-3"))).isEqualByComparingTo("-3");
        assertThat(StockRules.countDelta(d("2"), d("-3"))).isEqualByComparingTo("-2");
        assertThat(StockRules.countDelta(d("5"), d("4"))).isEqualByComparingTo("4");
    }

    @Test
    void reorderFillsUpToParOrTwiceTheMinimum() {
        assertThat(StockRules.suggest(d("3"), d("5"), d("20"))).isEqualByComparingTo("17");
        assertThat(StockRules.suggest(d("3"), d("5"), d("0"))).isEqualByComparingTo("7");
        assertThat(StockRules.suggest(d("30"), d("5"), d("20"))).isEqualByComparingTo("0");
    }

    @Test
    void lowIsRaisedOnlyWhenTheLevelCrossesItsMinimum() {
        assertThat(StockRules.crossedLow(d("6"), d("5"), d("5"))).isTrue();
        assertThat(StockRules.crossedLow(d("5"), d("4"), d("5"))).isFalse();
        assertThat(StockRules.crossedLow(d("6"), d("5"), d("0"))).isFalse();
    }

    @Test
    void moneyReadsInNaira() {
        assertThat(StockRules.naira(d("250000"))).isEqualTo("₦250,000");
        assertThat(StockRules.naira(d("1250.5"))).isEqualTo("₦1,250.50");
        assertThat(InventoryService.validPhone("0803 123 4567")).isTrue();
        assertThat(InventoryService.validPhone("+234 000 000 0000")).isFalse();
    }
}
