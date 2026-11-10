package com.hospio.finance.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.finance.entity.ExpenseCategory;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Budget against actual: spend grouped by code across spellings, every active category listed, extras last, totals the sums of the rows. */
class BudgetMathTest {

    private static ExpenseCategory cat(String label, int order, boolean active) {
        return ExpenseCategory.builder().code(ExpenseCategories.codeOf(label)).label(label).sortOrder(order).active(active).build();
    }

    private static final List<ExpenseCategory> LIST = List.of(cat("Fuel & power", 1, true), cat("Repairs", 2, true), cat("Marketing", 3, true),
            cat("Old stuff", 4, false), cat("Other", 5, true));

    @Test
    void spendOfEverySpellingLandsOnOneRow() {
        Map<String, BigDecimal> spend = new LinkedHashMap<>();
        spend.put("Fuel & power", new BigDecimal("120000"));
        spend.put("FUEL_AND_POWER", new BigDecimal("30000.50"));
        spend.put("REPAIRS", new BigDecimal("45000"));
        BudgetMath.Overview o = BudgetMath.overview("2026-11", LIST, Map.of("FUEL_AND_POWER", new BigDecimal("200000")), spend);
        BudgetMath.Row fuel = o.rows().get(0);
        assertThat(fuel.label()).isEqualTo("Fuel & power");
        assertThat(fuel.spent()).isEqualByComparingTo("150000.50");
        assertThat(fuel.budget()).isEqualByComparingTo("200000");
        assertThat(fuel.left()).isEqualByComparingTo("49999.50");
        assertThat(fuel.pct()).isEqualTo(75);
        assertThat(o.rows().get(1).spent()).isEqualByComparingTo("45000");
        assertThat(o.rows().get(1).pct()).isNull();
    }

    @Test
    void everyActiveCategoryInOrderThenExtras() {
        Map<String, BigDecimal> spend = Map.of("SUPPLIES", new BigDecimal("6000"), "Old stuff", new BigDecimal("1000"));
        BudgetMath.Overview o = BudgetMath.overview("2026-11", LIST, Map.of(), spend);
        assertThat(o.rows()).extracting(BudgetMath.Row::label).containsExactly("Fuel & power", "Repairs", "Marketing", "Other", "Old stuff", "Supplies");
        assertThat(o.rows().get(5).category()).isEqualTo("SUPPLIES");
    }

    @Test
    void totalsAreTheSumsOfTheRows() {
        Map<String, BigDecimal> spend = Map.of("Fuel & power", new BigDecimal("10.10"), "REPAIRS", new BigDecimal("20.20"), "SUPPLIES",
                new BigDecimal("5"));
        Map<String, BigDecimal> budgets = Map.of("FUEL_AND_POWER", new BigDecimal("100"), "MARKETING", new BigDecimal("50"));
        BudgetMath.Overview o = BudgetMath.overview("2026-11", LIST, budgets, spend);
        BigDecimal spent = o.rows().stream().map(BudgetMath.Row::spent).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal budget = o.rows().stream().map(BudgetMath.Row::budget).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(o.totals().spent()).isEqualByComparingTo(spent).isEqualByComparingTo("35.30");
        assertThat(o.totals().budget()).isEqualByComparingTo(budget).isEqualByComparingTo("150");
        assertThat(o.totals().left()).isEqualByComparingTo("114.70");
    }

    @Test
    void aMonthWithNothingListsTheCategoriesAtZero() {
        BudgetMath.Overview o = BudgetMath.overview("2026-11", LIST, Map.of(), Map.of());
        assertThat(o.rows()).hasSize(4);
        assertThat(o.totals().spent()).isEqualByComparingTo("0");
    }
}
