package com.hospio.fnb.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Today at the location's outlets, for the owner and manager dashboards of a lounge, bar or restaurant (docs/plan-venue-type.md phase 2).
 *
 * <p>{@code lastWeek} is the same weekday a week ago, only when something was sold that day (otherwise null: no made-up comparison);
 * {@code soFar} is what had been sold by this time of day, {@code day} the whole day. {@code takenByMethod} is money taken today at the
 * outlets (CASH, CARD, TRANSFER), whenever the sale was opened. {@code openTabs} are tabs not yet settled, any day; {@code stale} those
 * opened more than {@code staleHours} ago, oldest first. {@code unpaid} are sales still owing money, any day. {@code voids} are today's.
 */
public record VenueToday(LocalDate date, Instant at, Day today, LastWeek lastWeek, List<VenueReport.MethodRow> takenByMethod,
                         List<VenueReport.OutletRow> byOutlet, List<VenueReport.ItemRow> topItems, OpenTabs openTabs, Unpaid unpaid,
                         Voids voids, long priceChangesWaiting) {

    public record Day(long sales, BigDecimal sold, BigDecimal paid, BigDecimal unpaid, long cancelled, long itemsSold) {
    }

    public record LastWeek(LocalDate date, long soFarSales, BigDecimal soFar, long daySales, BigDecimal day) {
    }

    public record OpenTabs(long count, BigDecimal value, Instant oldestAt, int staleHours, long staleCount, List<Tab> stale) {
    }

    /** {@code balance} is what is still to pay on it. */
    public record Tab(String orderId, String number, String outletName, String guestName, String openedBy, Instant openedAt, BigDecimal balance) {
    }

    public record Unpaid(long count, BigDecimal amount) {
    }

    public record Voids(long count, BigDecimal amount, List<VenueReport.VoidRow> latest) {
    }
}
