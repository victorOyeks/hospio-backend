package com.hospio.fnb.rules;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** The outlets' business day is the Lagos day, as in finance. */
public final class Days {

    public static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private Days() {
    }

    public static LocalDate today() {
        return LocalDate.now(LAGOS);
    }

    public static Instant start(LocalDate date) {
        return date.atStartOfDay(LAGOS).toInstant();
    }

    public static Instant end(LocalDate date) {
        return date.plusDays(1).atStartOfDay(LAGOS).toInstant();
    }
}
