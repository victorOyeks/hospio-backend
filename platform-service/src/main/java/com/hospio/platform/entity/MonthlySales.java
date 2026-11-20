package com.hospio.platform.entity;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * "Average monthly sales (all outlets combined)" on the Book a demo form (docs/onboarding.md, "Applications"): a band the applicant
 * picks, required for new requests. Information for the team's call only: nothing is scored or declined on it. Requests sent before
 * the question existed have none ("Not asked").
 */
public enum MonthlySales {

    UNDER_1M("Under ₦1 million", 4),
    FROM_1M_TO_5M("₦1 million – ₦5 million", 3),
    FROM_5M_TO_20M("₦5 million – ₦20 million", 2),
    ABOVE_20M("Above ₦20 million", 1),
    PREFER_NOT("Prefer not to say", 5);

    /** The list filter for requests sent before the question existed (no band). */
    public static final String NOT_ASKED = "NOT_ASKED";
    public static final String NOT_ASKED_LABEL = "Not asked";
    /** Sort rank of a request with no band: after every band, Prefer not to say included. */
    public static final int NOT_ASKED_RANK = 6;
    /**
     * The rank in SQL (highest band first, Prefer not to say then Not asked last), for {@link HotelApplication#getMonthlySalesRank()}.
     * Kept in step with the ranks above by HotelApplicationServiceTest.
     */
    public static final String RANK_SQL = "(CASE monthly_sales WHEN 'ABOVE_20M' THEN 1 WHEN 'FROM_5M_TO_20M' THEN 2 WHEN 'FROM_1M_TO_5M' THEN 3 "
            + "WHEN 'UNDER_1M' THEN 4 WHEN 'PREFER_NOT' THEN 5 ELSE 6 END)";

    private final String label;
    private final int rank;

    MonthlySales(String label, int rank) {
        this.label = label;
        this.rank = rank;
    }

    public String label() {
        return label;
    }

    public int rank() {
        return rank;
    }

    /** The band for a code sent by the form or a filter (any case, trimmed), if it is one. */
    public static Optional<MonthlySales> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String c = raw.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(v -> v.name().equals(c)).findFirst();
    }

    /** The words for a stored code; "Not asked" when there is none. */
    public static String labelOf(String code) {
        return parse(code).map(MonthlySales::label).orElse(NOT_ASKED_LABEL);
    }
}
