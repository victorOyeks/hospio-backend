package com.hospio.fnb.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The venue reports (docs/plan-venue-type.md phase 2) over the sales opened in the Lagos days {@code from}..{@code to} at the location's
 * outlets (or one): sales by outlet, item, category, staff and hour of day; money taken by method; voids; discounts and complimentary
 * sales; and what the recipes say the items sold use from stock. Only real data: an empty period answers empty lists and zeros.
 *
 * <p>{@code totals}: {@code sales} counts the sales not cancelled and {@code sold} is their total (tax and service charge included), split
 * into {@code paid}, {@code onRooms} and {@code unpaid}, as in the sales history. {@code discounts}, {@code serviceCharge} and {@code tax}
 * are inside {@code sold}. {@code itemsSold} counts the units on live lines. {@code voids} and {@code voidAmount} count every void below.
 *
 * <p>Item revenue is the menu price times the quantity less any discount, before service charge (and before tax where prices exclude it),
 * so the items add up to the item sales, not to {@code sold}. Payments ({@code byMethod}) are by when the money was taken, which can be a
 * day after the sale was opened.
 */
public record VenueReport(LocalDate from, LocalDate to, String outletId, List<SalesHistory.OutletRef> outlets, Totals totals,
                          List<OutletRow> byOutlet, List<ItemRow> byItem, List<CategoryRow> byCategory, List<StaffRow> byStaff,
                          List<HourRow> byHour, List<MethodRow> byMethod, List<VoidRow> voids, boolean voidsTruncated,
                          List<DiscountRow> discounts, boolean discountsTruncated, List<StockUse> stockUse, int itemsWithRecipe,
                          int itemsWithoutRecipe) {

    public record Totals(long sales, BigDecimal sold, BigDecimal paid, BigDecimal unpaid, BigDecimal onRooms, long cancelled,
                         BigDecimal cancelledAmount, BigDecimal discounts, BigDecimal serviceCharge, BigDecimal tax, long itemsSold,
                         BigDecimal itemSales, long voids, BigDecimal voidAmount, long complimentary, BigDecimal complimentaryAmount) {
    }

    public record OutletRow(String outletId, String name, String kind, long sales, BigDecimal sold, BigDecimal paid, BigDecimal unpaid,
                            BigDecimal onRooms, long cancelled) {
    }

    /** {@code category} is the menu category ("Other" when the item has none or was deleted). */
    public record ItemRow(String menuItemId, String name, String category, String outletId, String outletName, long quantity, BigDecimal revenue) {
    }

    public record CategoryRow(String category, long quantity, BigDecimal revenue, int items) {
    }

    /**
     * Who opened the sales ({@code userId} empty when nobody is recorded) and what they voided: {@code voids} counts the orders and items
     * they cancelled, {@code voidAmount} what was on them.
     */
    public record StaffRow(String userId, String name, long sales, BigDecimal sold, BigDecimal paid, long cancelled, long voids,
                           BigDecimal voidAmount) {
    }

    /** {@code hour} 0-23 in Lagos time, when the sale was opened. */
    public record HourRow(int hour, long sales, BigDecimal sold) {
    }

    /** CASH, CARD or TRANSFER taken at the outlets; ROOM is what went on rooms (sales of the period). */
    public record MethodRow(String method, long count, BigDecimal amount) {
    }

    /**
     * A void: {@code kind} ORDER (a whole sale cancelled; {@code amount} is what was on it) or ITEM (one line taken off a sale that went on).
     * {@code by} is who cancelled it.
     */
    public record VoidRow(String kind, String orderId, String number, String outletName, Instant at, String item, long quantity,
                          BigDecimal amount, String reason, String byId, String by) {
    }

    /** A sale with a discount, or closed as complimentary ({@code complimentary}: nothing was charged; {@code total} is what it was worth). */
    /**
     * {@code byHand}: the part of {@code discount} given by hand (the rest is happy hour); {@code complimentaryItems} and
     * {@code complimentaryAmount}: items given free and what they were worth; {@code reasons}, {@code givenBy}, {@code approvedBy}: why,
     * who gave it and the manager who approved it (when it had to be asked for).
     */
    public record DiscountRow(String orderId, String number, String outletName, Instant at, BigDecimal discount, BigDecimal total,
                              boolean complimentary, String openedBy, BigDecimal byHand, long complimentaryItems, BigDecimal complimentaryAmount,
                              String reasons, String givenBy, String approvedBy) {
    }

    /** What the recipes of the items sold call for from one stock item (the quantity in the stock item's unit). */
    public record StockUse(String stockItemId, BigDecimal quantity) {
    }
}
