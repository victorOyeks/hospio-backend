package com.hospio.finance.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The closing report of a night audit, stored as JSON on the audit row ({@code night_audits.report}) and served raw in
 * {@link NightAuditView#report()}. Keys and nesting are the web app's contract: {@code outletSales} only when fnb-service
 * answered, {@code openOrdersCarried} only when a manager carried open tabs over, {@code openOrdersNote} only when they were
 * carried without the outlets being checked. Unknown keys are ignored so every report stored so far still reads.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NightAuditReport(String date, BigDecimal revenue, Map<String, BigDecimal> payments, Map<String, BigDecimal> revenueByRole,
                               int paymentsCount, BigDecimal expenses, Map<String, BigDecimal> expensesByCategory, BigDecimal net, Cash cash,
                               Occupancy occupancy, Guests guests, List<TillRow> tills, int balancesOwing,
                               @JsonInclude(JsonInclude.Include.NON_NULL) OutletSalesSummary outletSales, boolean carriedOpenTabs,
                               @JsonInclude(JsonInclude.Include.NON_NULL) List<CarriedOutlet> openOrdersCarried,
                               @JsonInclude(JsonInclude.Include.NON_NULL) String openOrdersNote, String closedBy) {

    /** An empty report: what an unreadable or missing stored report reads as. */
    public static NightAuditReport empty() {
        return new NightAuditReport(null, null, Map.of(), Map.of(), 0, null, Map.of(), null, null, null, null, List.of(), 0, null, false,
                null, null, null);
    }

    /** The guests block, never null. */
    public Guests guestsOrEmpty() {
        return guests == null ? new Guests(0, 0, 0, 0, 0) : guests;
    }

    /** The occupancy block, never null. */
    public Occupancy occupancyOrEmpty() {
        return occupancy == null ? new Occupancy(0, 0, null) : occupancy;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cash(BigDecimal expected, BigDecimal counted, BigDecimal variance, BigDecimal unattached) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Occupancy(int roomsOccupied, int rooms, Double pct) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Guests(int arrivals, int departures, int inHouse, int noShows, int markedNoShow) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TillRow(String role, BigDecimal expected, BigDecimal counted, BigDecimal variance) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CarriedOutlet(String outlet, int openTabs, int unsettled, List<CarriedOrder> orders) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CarriedOrder(String number, String kind, String tableNo, String roomNumber, BigDecimal total) {
    }
}
