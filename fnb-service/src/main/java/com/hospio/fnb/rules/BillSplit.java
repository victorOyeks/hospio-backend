package com.hospio.fnb.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Dividing a bill to the kobo (docs/plan-venue-type.md, phase 4). Every part but the last is rounded down to the kobo and the last
 * takes what is left, so the parts always add up to the bill exactly: ₦10,000 between three is 3,333.33 + 3,333.33 + 3,333.34.
 * The tax and the service charge inside the bill are shared the same way, in proportion to each part.
 */
public final class BillSplit {

    private BillSplit() {
    }

    /** One part: what the guest pays, and how much of it is tax and service charge. */
    public record Share(BigDecimal amount, BigDecimal tax, BigDecimal serviceCharge) {
    }

    /** {@code total} shared by {@code weights} (each ≥ 0, at least one above 0): rounded down, the last part takes the remainder. */
    public static List<BigDecimal> allocate(BigDecimal total, List<BigDecimal> weights) {
        if (weights == null || weights.isEmpty()) {
            throw new IllegalArgumentException("Nothing to share between");
        }
        BigDecimal sum = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.signum() <= 0 || weights.stream().anyMatch(w -> w.signum() < 0)) {
            throw new IllegalArgumentException("Weights must add up to more than nothing");
        }
        BigDecimal whole = total.setScale(2, RoundingMode.HALF_UP);
        List<BigDecimal> out = new ArrayList<>();
        BigDecimal given = BigDecimal.ZERO;
        for (int i = 0; i < weights.size(); i++) {
            BigDecimal part = i == weights.size() - 1 ? whole.subtract(given)
                    : whole.multiply(weights.get(i)).divide(sum, 2, RoundingMode.DOWN);
            out.add(part);
            given = given.add(part);
        }
        return out;
    }

    public static List<BigDecimal> equal(BigDecimal total, int people) {
        if (people < 1) {
            throw new IllegalArgumentException("At least one person");
        }
        List<BigDecimal> ones = new ArrayList<>();
        for (int i = 0; i < people; i++) {
            ones.add(BigDecimal.ONE);
        }
        return allocate(total, ones);
    }

    /** The parts with their share of {@code tax} and {@code serviceCharge} (what is inside the amount being divided). */
    public static List<Share> shares(List<BigDecimal> amounts, BigDecimal tax, BigDecimal serviceCharge) {
        boolean nothing = amounts.stream().allMatch(a -> a.signum() == 0);
        List<BigDecimal> weights = nothing ? amounts.stream().map(a -> BigDecimal.ONE).toList() : amounts;
        List<BigDecimal> taxes = allocate(tax == null ? BigDecimal.ZERO : tax, weights);
        List<BigDecimal> charges = allocate(serviceCharge == null ? BigDecimal.ZERO : serviceCharge, weights);
        List<Share> out = new ArrayList<>();
        for (int i = 0; i < amounts.size(); i++) {
            out.add(new Share(amounts.get(i), taxes.get(i), charges.get(i)));
        }
        return out;
    }

    /** The part of {@code component} (the bill's tax or service charge) that sits inside {@code portion} of a bill worth {@code total}. */
    public static BigDecimal inside(BigDecimal component, BigDecimal portion, BigDecimal total) {
        if (component == null || total == null || total.signum() <= 0 || portion.compareTo(total) >= 0) {
            return component == null ? BigDecimal.ZERO.setScale(2) : component.setScale(2, RoundingMode.HALF_UP);
        }
        return component.multiply(portion).divide(total, 2, RoundingMode.HALF_UP);
    }
}
