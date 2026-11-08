package com.hospio.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Naira arithmetic: two decimal places, half-up, never null. */
public final class Money {

    private Money() {
    }

    public static BigDecimal of(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : value.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal add(BigDecimal a, BigDecimal b) {
        return of(a).add(of(b));
    }

    public static BigDecimal subtract(BigDecimal a, BigDecimal b) {
        return of(a).subtract(of(b));
    }

    public static BigDecimal percent(BigDecimal amount, BigDecimal percent) {
        return of(of(amount).multiply(of(percent)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
    }

    public static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
