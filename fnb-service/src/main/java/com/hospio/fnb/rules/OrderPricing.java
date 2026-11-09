package com.hospio.fnb.rules;

import com.hospio.common.util.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * What an order costs. Each line is its price times quantity less its happy-hour discount ("priced"). With prices including tax the
 * tax is extracted from that (total = sum of priced); otherwise it is added on top. Either way
 * {@code subtotal - discount + tax = total}, and {@code subtotal - discount} (the part without tax) is what a room charge posts.
 */
public final class OrderPricing {

    private OrderPricing() {
    }

    /** A live line: {@code taxRate} is the percentage (item rate, else the hotel's VAT). */
    public record Input(BigDecimal unitPrice, int quantity, BigDecimal discount, BigDecimal taxRate) {
        public BigDecimal gross() {
            return Money.of(unitPrice.multiply(BigDecimal.valueOf(quantity)));
        }

        public BigDecimal priced() {
            return Money.of(gross().subtract(discount == null ? BigDecimal.ZERO : discount));
        }
    }

    public record Totals(BigDecimal subtotal, BigDecimal tax, BigDecimal discount, BigDecimal total) {
    }

    public static Totals price(List<Input> lines, boolean pricesIncludeTax) {
        BigDecimal priced = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        for (Input l : lines) {
            BigDecimal p = l.priced();
            BigDecimal rate = l.taxRate() == null ? BigDecimal.ZERO : l.taxRate();
            BigDecimal t = pricesIncludeTax ? extract(p, rate) : Money.percent(p, rate);
            priced = priced.add(p);
            tax = tax.add(t);
            discount = discount.add(l.discount() == null ? BigDecimal.ZERO : l.discount());
        }
        BigDecimal total = pricesIncludeTax ? priced : priced.add(tax);
        BigDecimal subtotal = total.subtract(tax).add(discount);
        return new Totals(Money.of(subtotal), Money.of(tax), Money.of(discount), Money.of(total));
    }

    /** The tax inside a tax-inclusive amount: amount * rate / (100 + rate). */
    public static BigDecimal extract(BigDecimal amount, BigDecimal ratePct) {
        if (ratePct == null || ratePct.signum() <= 0) {
            return Money.of(BigDecimal.ZERO);
        }
        return amount.multiply(ratePct).divide(BigDecimal.valueOf(100).add(ratePct), 2, RoundingMode.HALF_UP);
    }

    /** The happy-hour discount for a line: {@code pct} percent of its gross. */
    public static BigDecimal happyHourDiscount(BigDecimal gross, BigDecimal pct) {
        if (pct == null || pct.signum() <= 0) {
            return Money.of(BigDecimal.ZERO);
        }
        return Money.percent(gross, pct.min(BigDecimal.valueOf(100)));
    }
}
