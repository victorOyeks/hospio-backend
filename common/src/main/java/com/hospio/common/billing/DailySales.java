package com.hospio.common.billing;

import java.math.BigDecimal;
import java.util.List;

/**
 * One service's share of what a hotel sold on one Lagos day, VAT excluded: the basis of Hospio's daily fee
 * (docs/plan-daily-fees.md). Each service answers {@code GET /internal/sales/daily?hotelId=&date=} with its lines; a line's
 * amount can be negative (a void, a refunded no-show deposit). Anything charged to a room is counted once, on the folio.
 */
public record DailySales(List<Line> lines) {

    /** {@code source}: FOLIO_ROOM, FOLIO_EXTRAS, FOLIO_DISCOUNTS, FOLIO_VOIDS, OUTLETS, LAUNDRY, NO_SHOW_DEPOSITS. */
    public record Line(String source, String label, BigDecimal amount, long count) {
    }

    public static DailySales of(Line... lines) {
        return new DailySales(List.of(lines));
    }
}
