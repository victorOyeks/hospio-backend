package com.hospio.fnb.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Prices with tax included are the total and the tax is extracted; prices without tax get it added; happy hour comes off per line. */
class OrderPricingTest {

    private static OrderPricing.Input line(String unit, int qty, String discount, String taxRate) {
        return new OrderPricing.Input(new BigDecimal(unit), qty, new BigDecimal(discount), new BigDecimal(taxRate));
    }

    @Test
    void taxIncludedTheTotalIsTheSumOfThePricesAndTheTaxIsExtracted() {
        OrderPricing.Totals t = OrderPricing.price(List.of(line("1075.00", 2, "0", "7.5")), true);

        assertThat(t.total()).isEqualByComparingTo("2150.00");
        assertThat(t.tax()).isEqualByComparingTo("150.00");
        assertThat(t.subtotal()).isEqualByComparingTo("2000.00");
        assertThat(t.discount()).isEqualByComparingTo("0");
    }

    @Test
    void taxExcludedItIsAddedOnTop() {
        OrderPricing.Totals t = OrderPricing.price(List.of(line("1000.00", 2, "0", "7.5")), false);

        assertThat(t.subtotal()).isEqualByComparingTo("2000.00");
        assertThat(t.tax()).isEqualByComparingTo("150.00");
        assertThat(t.total()).isEqualByComparingTo("2150.00");
    }

    @Test
    void happyHourDiscountComesOffTheLineBeforeTax() {
        BigDecimal discount = OrderPricing.happyHourDiscount(new BigDecimal("3000.00"), new BigDecimal("20"));
        assertThat(discount).isEqualByComparingTo("600.00");

        OrderPricing.Totals included = OrderPricing.price(List.of(new OrderPricing.Input(new BigDecimal("1500"), 2, discount, new BigDecimal("7.5"))), true);
        assertThat(included.total()).isEqualByComparingTo("2400.00");
        assertThat(included.discount()).isEqualByComparingTo("600.00");
        assertThat(included.subtotal().subtract(included.discount()).add(included.tax())).isEqualByComparingTo(included.total());

        OrderPricing.Totals excluded = OrderPricing.price(List.of(new OrderPricing.Input(new BigDecimal("1500"), 2, discount, new BigDecimal("10"))), false);
        assertThat(excluded.total()).isEqualByComparingTo("2640.00");
        assertThat(excluded.subtotal().subtract(excluded.discount()).add(excluded.tax())).isEqualByComparingTo(excluded.total());
    }

    @Test
    void aLineWithoutATaxRateAddsNoTax() {
        OrderPricing.Totals t = OrderPricing.price(List.of(new OrderPricing.Input(new BigDecimal("500"), 3, BigDecimal.ZERO, null)), false);

        assertThat(t.tax()).isEqualByComparingTo("0");
        assertThat(t.total()).isEqualByComparingTo("1500.00");
    }
}
