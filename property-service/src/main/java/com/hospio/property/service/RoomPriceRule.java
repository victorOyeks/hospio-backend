package com.hospio.property.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** When a manager's change of a room type's base price goes to the owner (Hotel setup → Approval limits). */
public final class RoomPriceRule {

    private RoomPriceRule() {
    }

    /** {@code needsOwner}: rateChangesNeedOwner; {@code pct}: rateChangeOwnerPct, where 0 (or less) means every change. */
    public record Policy(boolean needsOwner, BigDecimal pct) {
    }

    /** The change in percent of the old price (absolute); 100 when the old price was zero. */
    public static BigDecimal changePct(BigDecimal oldPrice, BigDecimal newPrice) {
        if (oldPrice == null || oldPrice.signum() == 0) {
            return BigDecimal.valueOf(100);
        }
        return newPrice.subtract(oldPrice).abs().multiply(BigDecimal.valueOf(100)).divide(oldPrice, 4, RoundingMode.HALF_UP);
    }

    /** True when the change waits for the owner: the rule is on and the change is above the threshold (any change at 0). */
    public static boolean toOwner(Policy policy, BigDecimal oldPrice, BigDecimal newPrice) {
        if (!policy.needsOwner() || oldPrice == null || newPrice == null || oldPrice.compareTo(newPrice) == 0) {
            return false;
        }
        BigDecimal pct = policy.pct() == null ? BigDecimal.ZERO : policy.pct();
        return pct.signum() <= 0 || changePct(oldPrice, newPrice).compareTo(pct) > 0;
    }
}
