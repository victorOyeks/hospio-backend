package com.hospio.finance.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A till should hold the float plus cash payments and pay-ins, minus pay-outs, cash refunds and cash expenses. */
class TillMathTest {

    private static List<BigDecimal> amounts(String... values) {
        return java.util.Arrays.stream(values).map(BigDecimal::new).toList();
    }

    @Test
    void expectedIsFloatPlusInMinusOut() {
        // payment 20,000, pay-in 5,000, pay-out 3,000, refund 4,500
        List<BigDecimal> entries = amounts("20000", "5000", "-3000", "-4500");
        assertThat(TillMath.cashIn(entries)).isEqualByComparingTo("25000");
        assertThat(TillMath.cashOut(entries)).isEqualByComparingTo("7500");
        assertThat(TillMath.expected(new BigDecimal("10000"), entries)).isEqualByComparingTo("27500.00");
    }

    @Test
    void anEmptyTillHoldsItsFloat() {
        assertThat(TillMath.expected(new BigDecimal("15000"), List.of())).isEqualByComparingTo("15000");
    }

    @Test
    void varianceIsCountedMinusExpected() {
        assertThat(TillMath.variance(new BigDecimal("27000"), new BigDecimal("27500"))).isEqualByComparingTo("-500");
        assertThat(TillMath.variance(new BigDecimal("27500"), new BigDecimal("27500"))).isEqualByComparingTo("0");
        assertThat(TillMath.variance(new BigDecimal("28000"), new BigDecimal("27500"))).isEqualByComparingTo("500");
    }
}
