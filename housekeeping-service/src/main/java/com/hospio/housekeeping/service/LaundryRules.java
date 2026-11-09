package com.hospio.housekeeping.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.util.Money;
import com.hospio.housekeeping.entity.LaundryOrder;
import com.hospio.housekeeping.entity.LinenBatch;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Guest laundry and linen-batch rules (docs/laundry-maintenance.md): prices, totals, status moves, settlement, batch arithmetic. Pure. */
public final class LaundryRules {

    private LaundryRules() {
    }

    private static final Map<LaundryOrder.Status, LaundryOrder.Status> NEXT = Map.of(LaundryOrder.Status.RECEIVED, LaundryOrder.Status.IN_PROGRESS,
            LaundryOrder.Status.IN_PROGRESS, LaundryOrder.Status.READY, LaundryOrder.Status.READY, LaundryOrder.Status.DELIVERED);

    /** The express price when none is set: one and a half times the normal price, to the naira. */
    public static BigDecimal defaultExpress(BigDecimal price) {
        return price.multiply(new BigDecimal("1.5")).setScale(0, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal unitPrice(BigDecimal price, BigDecimal expressPrice, boolean express) {
        if (!express) {
            return Money.of(price);
        }
        return Money.of(expressPrice == null ? defaultExpress(price) : expressPrice);
    }

    public record Line(String priceId, String name, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {
        public static Line of(String priceId, String name, BigDecimal quantity, BigDecimal unitPrice) {
            return new Line(priceId, name, quantity, Money.of(unitPrice), Money.of(quantity.multiply(unitPrice)));
        }
    }

    public static BigDecimal total(List<Line> lines) {
        return Money.of(lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /** When the order is promised back if the desk doesn't say: 24 hours, express 6 hours. */
    public static Duration turnaround(boolean express) {
        return express ? Duration.ofHours(6) : Duration.ofHours(24);
    }

    public static boolean settled(LaundryOrder o) {
        return o.isChargedToRoom() || o.isPaid();
    }

    /** Forward one step at a time; delivering needs the order settled. */
    public static void checkMove(LaundryOrder o, LaundryOrder.Status to) {
        String n = o.getNumber() == null ? "This order" : o.getNumber();
        if (o.getStatus() == LaundryOrder.Status.CANCELLED) {
            throw new BadRequestException(n + " is cancelled");
        }
        if (o.getStatus() == LaundryOrder.Status.DELIVERED) {
            throw new BadRequestException(n + " is already delivered");
        }
        if (to == null || NEXT.get(o.getStatus()) != to) {
            throw new BadRequestException(n + " is " + label(o.getStatus()) + " · next it goes " + label(NEXT.get(o.getStatus())));
        }
        if (to == LaundryOrder.Status.DELIVERED && !settled(o) && o.getTotal().signum() > 0) {
            throw new BadRequestException("Charge it to the room or take payment before it goes back to the guest");
        }
    }

    /** Charging to the room or recording a payment: once, never on a cancelled order, never for nothing. */
    public static void checkSettle(LaundryOrder o) {
        String n = o.getNumber() == null ? "This order" : o.getNumber();
        if (o.getStatus() == LaundryOrder.Status.CANCELLED) {
            throw new BadRequestException(n + " is cancelled");
        }
        if (o.isChargedToRoom()) {
            throw new BadRequestException(n + " is already charged to the room");
        }
        if (o.isPaid()) {
            throw new BadRequestException(n + " is already paid");
        }
        if (o.getTotal() == null || o.getTotal().signum() <= 0) {
            throw new BadRequestException("There is nothing to charge on " + n);
        }
    }

    /** Cancel only before anything reached the guest's bill or the till. */
    public static void checkCancel(LaundryOrder o) {
        String n = o.getNumber() == null ? "This order" : o.getNumber();
        if (o.getStatus() == LaundryOrder.Status.CANCELLED) {
            throw new BadRequestException(n + " is already cancelled");
        }
        if (o.getStatus() == LaundryOrder.Status.DELIVERED) {
            throw new BadRequestException(n + " is already delivered");
        }
        if (o.isChargedToRoom()) {
            throw new BadRequestException(n + " is already on the guest's bill · it can't be cancelled here");
        }
        if (o.isPaid()) {
            throw new BadRequestException(n + " is already paid · refund the payment instead");
        }
    }

    public static String label(LaundryOrder.Status s) {
        return s == null ? "nowhere" : s.name().toLowerCase().replace('_', ' ');
    }

    // ---- linen batches -------------------------------------------------------------------------------------

    /** One linen type on a batch. {@code lost} is what never came back: missing at receipt plus anything written off at close. */
    public record BatchLine(String itemId, String name, String unit, BigDecimal sent, BigDecimal received, BigDecimal returned, BigDecimal condemned,
                            BigDecimal lost, BigDecimal unitCost) {

        /** Still at the laundry: received and not yet returned, condemned or written off. */
        public BigDecimal outstanding() {
            return z(received).subtract(z(returned)).subtract(z(condemned)).subtract(z(lostAtLaundry()));
        }

        /** Lost after the laundry received it (written off at close); missing at receipt is sent − received. */
        public BigDecimal lostAtLaundry() {
            return z(lost).subtract(missing()).max(BigDecimal.ZERO);
        }

        public BigDecimal missing() {
            return received == null ? BigDecimal.ZERO : z(sent).subtract(received).max(BigDecimal.ZERO);
        }

        public BatchLine with(BigDecimal received, BigDecimal returned, BigDecimal condemned, BigDecimal lost) {
            return new BatchLine(itemId, name, unit, sent, received, returned, condemned, lost, unitCost);
        }
    }

    /** RETURNED once nothing is outstanding on any line, PART_RETURNED while something is. */
    public static LinenBatch.Status afterReturn(List<BatchLine> lines) {
        return lines.stream().allMatch(l -> l.outstanding().signum() == 0) ? LinenBatch.Status.RETURNED : LinenBatch.Status.PART_RETURNED;
    }

    public static BigDecimal missing(List<BatchLine> lines) {
        return lines.stream().map(BatchLine::missing).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal z(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
