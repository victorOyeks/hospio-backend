package com.hospio.fnb.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discounts given by hand on a sale (docs/plan-venue-type.md, phase 4). A line's {@code base} is its price times quantity less what
 * happy hour already took off; a discount by hand comes off that, and a discount on the whole bill is shared over what is left of
 * every line. The limit is judged per line: what was given by hand plus its share of the bill's discount, as a percentage of the base.
 */
public final class Discounts {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Discounts() {
    }

    /** A live line: gross (price × quantity), what happy hour took off, and what was given by hand on the line itself. */
    public record Line(String id, BigDecimal gross, BigDecimal happyHour, BigDecimal manual) {
        public BigDecimal base() {
            return gross.subtract(happyHour).max(BigDecimal.ZERO);
        }

        /** What a discount on the whole bill is taken from. */
        public BigDecimal left() {
            return base().subtract(manual).max(BigDecimal.ZERO);
        }
    }

    /** {@code pct} percent of {@code base}, or {@code amount} (never more than the base). */
    public static BigDecimal lineDiscount(BigDecimal base, BigDecimal pct, BigDecimal amount) {
        if (pct != null && pct.signum() > 0) {
            return base.multiply(pct.min(HUNDRED)).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        }
        if (amount != null && amount.signum() > 0) {
            return amount.min(base).setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO.setScale(2);
    }

    /**
     * Each line's share of a discount on the whole bill. A percentage is taken per line; an amount is shared in proportion to what is
     * left of each line, the last line taking the remainder so the shares add up to the amount (capped at what the bill is worth).
     */
    public static Map<String, BigDecimal> billShares(List<Line> lines, BigDecimal pct, BigDecimal amount) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        boolean byPct = pct != null && pct.signum() > 0;
        boolean byAmount = !byPct && amount != null && amount.signum() > 0;
        BigDecimal left = lines.stream().map(Line::left).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (byAmount && left.signum() > 0) {
            List<Line> worth = lines.stream().filter(l -> l.left().signum() > 0).toList();
            List<BigDecimal> shares = BillSplit.allocate(amount.min(left), worth.stream().map(Line::left).toList());
            List<BigDecimal> capped = new ArrayList<>(shares);
            for (int i = 0; i < worth.size(); i++) {
                out.put(worth.get(i).id(), capped.get(i).min(worth.get(i).left()));
            }
        }
        for (Line l : lines) {
            if (byPct) {
                out.put(l.id(), l.left().multiply(pct.min(HUNDRED)).divide(HUNDRED, 2, RoundingMode.HALF_UP));
            } else {
                out.putIfAbsent(l.id(), BigDecimal.ZERO.setScale(2));
            }
        }
        return out;
    }

    /** The largest share of any line given away by hand (line discount + bill share), as a percentage of its base. */
    public static BigDecimal highestPct(List<Line> lines, Map<String, BigDecimal> billShares) {
        BigDecimal top = BigDecimal.ZERO;
        for (Line l : lines) {
            if (l.base().signum() <= 0) {
                continue;
            }
            BigDecimal given = l.manual().add(billShares.getOrDefault(l.id(), BigDecimal.ZERO));
            top = top.max(given.multiply(HUNDRED).divide(l.base(), 2, RoundingMode.HALF_UP));
        }
        return top;
    }

    /** Within the limit, allowing a kobo of rounding (10 % of ₦333 is ₦33.30, which reads back as 10.00 %). */
    public static boolean within(BigDecimal pct, BigDecimal limitPct) {
        return limitPct != null && pct.compareTo(limitPct.add(new BigDecimal("0.05"))) <= 0;
    }
}
