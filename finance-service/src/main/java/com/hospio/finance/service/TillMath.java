package com.hospio.finance.service;

import com.hospio.common.util.Money;
import java.math.BigDecimal;
import java.util.Collection;

/** What a till should hold: the float plus cash in (payments, pay-ins) minus cash out (pay-outs, refunds, cash expenses). */
public final class TillMath {

    private TillMath() {
    }

    public static BigDecimal cashIn(Collection<BigDecimal> entryAmounts) {
        return Money.of(entryAmounts.stream().filter(a -> a.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /** Positive number: the total of the negative entries. */
    public static BigDecimal cashOut(Collection<BigDecimal> entryAmounts) {
        return Money.of(entryAmounts.stream().filter(a -> a.signum() < 0).map(BigDecimal::negate).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    public static BigDecimal expected(BigDecimal openingFloat, Collection<BigDecimal> entryAmounts) {
        return Money.of(Money.of(openingFloat).add(cashIn(entryAmounts)).subtract(cashOut(entryAmounts)));
    }

    /**
     * Counted minus expected. Under NGN 1 either way is a match (0): split bills leave kobo in what a drawer should hold
     * (3,333.33) and there are no kobo coins to count, so such a till could never close without a "short NGN 0" note.
     */
    public static BigDecimal variance(BigDecimal counted, BigDecimal expected) {
        BigDecimal v = Money.subtract(counted, expected);
        return v.abs().compareTo(BigDecimal.ONE) < 0 ? Money.of(BigDecimal.ZERO) : v;
    }
}
