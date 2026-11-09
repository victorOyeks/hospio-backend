package com.hospio.inventory.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** The money and quantity rules of the store, kept apart so they can be tested on their own (docs/inventory-stores.md · Rules). */
public final class StockRules {

    private StockRules() {
    }

    public enum Caller { STAFF, MANAGER, OWNER }

    /**
     * Who has to approve a purchase order before it goes to the supplier: null when it can go now (at or under the limit, or the owner
     * raised it), the manager when the storekeeper raised it, the owner when a manager raised it.
     */
    public static String approverFor(BigDecimal total, BigDecimal limit, Caller caller) {
        if (caller == Caller.OWNER || limit == null || total.compareTo(limit) <= 0) {
            return null;
        }
        return caller == Caller.MANAGER ? "OWNER" : "MANAGER";
    }

    /** Weighted average cost after receiving {@code qty} at {@code cost} on top of {@code onHand} held at {@code oldCost}. */
    public static BigDecimal weightedCost(BigDecimal onHand, BigDecimal oldCost, BigDecimal qty, BigDecimal cost) {
        BigDecimal held = onHand == null || onHand.signum() < 0 ? BigDecimal.ZERO : onHand;
        if (oldCost == null || held.signum() == 0) {
            return cost.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal together = held.add(qty);
        if (together.signum() == 0) {
            return cost.setScale(2, RoundingMode.HALF_UP);
        }
        return held.multiply(oldCost).add(qty.multiply(cost)).divide(together, 2, RoundingMode.HALF_UP);
    }

    /** The movement an approved count posts: the counted difference applied to today's level, never taking it below zero. */
    public static BigDecimal countDelta(BigDecimal current, BigDecimal variance) {
        BigDecimal after = current.add(variance);
        return (after.signum() < 0 ? BigDecimal.ZERO : after).subtract(current);
    }

    /** How much to reorder: up to the par level, or twice the minimum when there is no par; never below zero. */
    public static BigDecimal suggest(BigDecimal quantity, BigDecimal minLevel, BigDecimal parLevel) {
        BigDecimal target = parLevel != null && parLevel.signum() > 0 ? parLevel : minLevel.multiply(BigDecimal.valueOf(2));
        BigDecimal s = target.subtract(quantity);
        return s.signum() < 0 ? BigDecimal.ZERO : s.setScale(2, RoundingMode.HALF_UP);
    }

    /** STOCK_LOW is raised once, when a level crosses down to its minimum (not on every movement below it). */
    public static boolean crossedLow(BigDecimal before, BigDecimal after, BigDecimal minLevel) {
        return minLevel != null && minLevel.signum() > 0 && after.compareTo(minLevel) <= 0 && before.compareTo(minLevel) > 0;
    }

    /** ₦12,500 or ₦12,500.50 */
    public static String naira(BigDecimal amount) {
        BigDecimal a = amount == null ? BigDecimal.ZERO : amount.setScale(2, RoundingMode.HALF_UP);
        String s = a.stripTrailingZeros().scale() <= 0 ? String.format("%,d", a.longValue()) : String.format("%,.2f", a);
        return "₦" + s;
    }

    /** 12 or 12.5 */
    public static String qty(BigDecimal q) {
        return q == null ? "0" : q.stripTrailingZeros().toPlainString();
    }
}
