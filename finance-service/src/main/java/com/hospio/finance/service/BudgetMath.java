package com.hospio.finance.service;

import com.hospio.common.util.Money;
import com.hospio.finance.entity.ExpenseCategory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Budget against actual for one month: a row per active category in list order (budget 0 when none is set), then every other category
 * money was spent on, by label, (an archived one, or a code no list carries, read as words). Spend is grouped by {@link ExpenseCategories#codeOf},
 * so "Fuel &amp; power" and FUEL_AND_POWER are one row. Totals are the sums of the rows.
 */
public final class BudgetMath {

    private BudgetMath() {
    }

    public record Row(String category, String label, BigDecimal budget, BigDecimal spent, BigDecimal left, Integer pct) {
    }

    public record Totals(BigDecimal budget, BigDecimal spent, BigDecimal left) {
    }

    public record Overview(String month, List<Row> rows, Totals totals) {
    }

    /**
     * @param categories every category of the hotel (archived included), in list order
     * @param budgets    category code (or any spelling) → budget for the month
     * @param spend      stored category spelling → money spent in the month
     */
    public static Overview overview(String month, List<ExpenseCategory> categories, Map<String, BigDecimal> budgets, Map<String, BigDecimal> spend) {
        Map<String, BigDecimal> budgetByCode = new LinkedHashMap<>();
        budgets.forEach((k, v) -> budgetByCode.merge(ExpenseCategories.codeOf(k), Money.of(v), BigDecimal::add));
        Map<String, BigDecimal> spentByCode = new LinkedHashMap<>();
        Map<String, String> firstSpelling = new LinkedHashMap<>();
        spend.forEach((k, v) -> {
            String code = ExpenseCategories.codeOf(k);
            spentByCode.merge(code, Money.of(v), BigDecimal::add);
            firstSpelling.putIfAbsent(code, k);
        });
        Map<String, ExpenseCategory> byCode = new LinkedHashMap<>();
        categories.forEach(c -> byCode.put(c.getCode(), c));

        List<Row> rows = new ArrayList<>();
        java.util.Set<String> done = new java.util.HashSet<>();
        for (ExpenseCategory c : categories) {
            if (!c.isActive()) {
                continue;
            }
            rows.add(row(c.getCode(), c.getLabel(), budgetByCode.get(c.getCode()), spentByCode.get(c.getCode())));
            done.add(c.getCode());
        }
        java.util.Set<String> extras = new java.util.TreeSet<>();
        extras.addAll(budgetByCode.keySet());
        extras.addAll(spentByCode.keySet());
        List<Row> extraRows = new ArrayList<>();
        for (String code : extras) {
            if (done.contains(code) || code.isEmpty()) {
                continue;
            }
            BigDecimal b = budgetByCode.get(code);
            BigDecimal s = spentByCode.get(code);
            if ((b == null || b.signum() == 0) && (s == null || s.signum() == 0)) {
                continue;
            }
            ExpenseCategory known = byCode.get(code);
            String label = known != null ? known.getLabel() : ExpenseCategories.humanise(firstSpelling.getOrDefault(code, code));
            extraRows.add(row(code, label, b, s));
        }
        extraRows.sort(java.util.Comparator.comparing(Row::label, String.CASE_INSENSITIVE_ORDER));
        rows.addAll(extraRows);
        BigDecimal tb = Money.of(rows.stream().map(Row::budget).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal ts = Money.of(rows.stream().map(Row::spent).reduce(BigDecimal.ZERO, BigDecimal::add));
        return new Overview(month, rows, new Totals(tb, ts, Money.of(tb.subtract(ts))));
    }

    static Row row(String code, String label, BigDecimal budget, BigDecimal spent) {
        BigDecimal b = Money.of(budget == null ? BigDecimal.ZERO : budget);
        BigDecimal s = Money.of(spent == null ? BigDecimal.ZERO : spent);
        Integer pct = b.signum() > 0 ? s.multiply(BigDecimal.valueOf(100)).divide(b, 0, RoundingMode.HALF_UP).intValue() : null;
        return new Row(code, label, b, s, Money.of(b.subtract(s)), pct);
    }
}
