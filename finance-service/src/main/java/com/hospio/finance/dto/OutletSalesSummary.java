package com.hospio.finance.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * What the restaurant and bar sold on a day, as fnb-service's {@code GET /internal/sales} answers it (its {@code SalesSummary}):
 * every field of that record, so finance passes it on (today's money, the sales screen, the night-audit report) in the same shape.
 * {@code kind} is the outlet's kind (BAR, KITCHEN…) as text.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OutletSalesSummary(LocalDate date, List<Outlet> outlets, Totals totals) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TopItem(String name, int qty, BigDecimal sales) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Outlet(String outletId, String name, String kind, int orders, BigDecimal sales, BigDecimal paidAtOutlet,
                         BigDecimal chargedToRooms, int cancelled, Map<String, BigDecimal> byMethod, List<TopItem> topItems) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Totals(int orders, BigDecimal sales, BigDecimal paidAtOutlet, BigDecimal chargedToRooms, int cancelled,
                         Map<String, BigDecimal> byMethod) {
    }
}
