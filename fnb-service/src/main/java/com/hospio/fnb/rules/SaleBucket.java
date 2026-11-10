package com.hospio.fnb.rules;

import com.hospio.common.exception.BadRequestException;
import com.hospio.fnb.entity.Order;
import com.hospio.fnb.entity.Outlet;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Where a sale stands for the sales history. First match wins:
 * <ol>
 *   <li>CANCELLED: the order was cancelled;</li>
 *   <li>ON_ROOM: it went on a guest's room;</li>
 *   <li>PAID: paid in full, or closed as complimentary (nothing to pay);</li>
 *   <li>PART_PAID: some money taken, some still due;</li>
 *   <li>IN_PROGRESS: nothing due yet: an open tab, a kitchen or restaurant ticket not yet served, or an order worth nothing so far;</li>
 *   <li>TO_PAY: everything else: a bar sale (not a tab) or a served ticket waiting for its money.</li>
 * </ol>
 * {@link #SQL} is the same rule for the query; both must change together (SaleBucketTest checks them against each other's cases).
 */
public enum SaleBucket {
    PAID, TO_PAY, PART_PAID, ON_ROOM, CANCELLED, IN_PROGRESS;

    /** The rule over {@code o} (fnb.orders) and {@code ou} (fnb.outlets). */
    public static final String SQL = "CASE"
            + " WHEN o.status = 'CANCELLED' THEN 'CANCELLED'"
            + " WHEN o.charged_to_room THEN 'ON_ROOM'"
            + " WHEN o.paid OR o.settled_by = 'COMPLIMENTARY' THEN 'PAID'"
            + " WHEN o.paid_amount > 0 THEN 'PART_PAID'"
            + " WHEN o.total <= 0 OR o.kind = 'TAB' OR (ou.kind <> 'BAR' AND o.status IN ('OPEN', 'SENT', 'READY')) THEN 'IN_PROGRESS'"
            + " ELSE 'TO_PAY' END";

    /** Money taken at the outlet, what went on a room and what is still due; the three add up to the total of a sale not cancelled. */
    public static final String PAID_PART_SQL = "CASE WHEN o.status = 'CANCELLED' THEN 0 WHEN o.paid THEN o.total ELSE LEAST(GREATEST(o.paid_amount, 0), o.total) END";
    public static final String ROOM_PART_SQL = "CASE WHEN o.status <> 'CANCELLED' AND o.charged_to_room AND NOT o.paid"
            + " THEN o.total - LEAST(GREATEST(o.paid_amount, 0), o.total) ELSE 0 END";
    public static final String UNPAID_PART_SQL = "CASE WHEN o.status <> 'CANCELLED' AND NOT o.paid AND NOT o.charged_to_room"
            + " AND COALESCE(o.settled_by, '') <> 'COMPLIMENTARY' THEN GREATEST(o.total - GREATEST(o.paid_amount, 0), 0) ELSE 0 END";

    /** Still owes money: what the sidebar counts and what stands out from earlier shifts. */
    public static final Set<SaleBucket> UNPAID = EnumSet.of(TO_PAY, PART_PAID);

    public static SaleBucket of(Order o, Outlet.Kind outletKind) {
        if (o.getStatus() == Order.Status.CANCELLED) {
            return CANCELLED;
        }
        if (o.isChargedToRoom()) {
            return ON_ROOM;
        }
        if (o.isPaid() || "COMPLIMENTARY".equals(o.getSettledBy())) {
            return PAID;
        }
        if (paidAmount(o).signum() > 0) {
            return PART_PAID;
        }
        boolean working = o.getStatus() == Order.Status.OPEN || o.getStatus() == Order.Status.SENT || o.getStatus() == Order.Status.READY;
        if (o.getTotal().signum() <= 0 || o.getKind() == Order.Kind.TAB || (outletKind != Outlet.Kind.BAR && working)) {
            return IN_PROGRESS;
        }
        return TO_PAY;
    }

    /** The parts of {@code o}'s total: paid at the outlet, on a room, still due (all zero when cancelled). */
    public record Parts(BigDecimal paid, BigDecimal onRoom, BigDecimal unpaid) {
    }

    public static Parts parts(Order o) {
        BigDecimal zero = BigDecimal.ZERO;
        if (o.getStatus() == Order.Status.CANCELLED) {
            return new Parts(zero, zero, zero);
        }
        BigDecimal taken = paidAmount(o).max(zero).min(o.getTotal());
        BigDecimal paid = o.isPaid() ? o.getTotal() : taken;
        BigDecimal room = o.isChargedToRoom() && !o.isPaid() ? o.getTotal().subtract(taken) : zero;
        BigDecimal unpaid = !o.isPaid() && !o.isChargedToRoom() && !"COMPLIMENTARY".equals(o.getSettledBy())
                ? o.getTotal().subtract(paidAmount(o).max(zero)).max(zero) : zero;
        return new Parts(paid, room, unpaid);
    }

    /** The status filter: one bucket, or UNPAID for TO_PAY and PART_PAID together; null or blank for every bucket. */
    public static Set<SaleBucket> parseFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return EnumSet.allOf(SaleBucket.class);
        }
        Set<SaleBucket> out = EnumSet.noneOf(SaleBucket.class);
        for (String part : raw.split(",")) {
            String s = part.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
            if (s.isEmpty()) {
                continue;
            }
            if (s.equals("UNPAID")) {
                out.addAll(UNPAID);
                continue;
            }
            try {
                out.add(SaleBucket.valueOf(s));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Unknown status " + part.trim() + ". Use PAID, TO_PAY, PART_PAID, ON_ROOM, CANCELLED, IN_PROGRESS or UNPAID");
            }
        }
        return out.isEmpty() ? EnumSet.allOf(SaleBucket.class) : out;
    }

    private static BigDecimal paidAmount(Order o) {
        return o.getPaidAmount() == null ? BigDecimal.ZERO : o.getPaidAmount();
    }
}
