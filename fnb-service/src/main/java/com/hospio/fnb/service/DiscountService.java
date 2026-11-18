package com.hospio.fnb.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.util.Money;
import com.hospio.fnb.client.OutletSettings;
import com.hospio.fnb.dto.Bill;
import com.hospio.fnb.entity.Order;
import com.hospio.fnb.entity.OrderLine;
import com.hospio.fnb.entity.Outlet;
import com.hospio.fnb.entity.SaleRequest;
import com.hospio.fnb.repository.OrderLineRepository;
import com.hospio.fnb.repository.OrderRepository;
import com.hospio.fnb.rules.Discounts;
import com.hospio.fnb.rules.OutletAccess;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Discounts and complimentary items given by hand on a sale (docs/plan-venue-type.md, phase 4). Managers and owners give any; the
 * roles in Hotel setup ("Who gives a discount": cashier and bar by default) give up to the approval limit (APPROVAL_LIMITS
 * discountPct, 10 %); more than that, a complimentary item, or anyone else is a request a manager decides ({@link SaleRequestService}).
 * A reason is always kept, with who gave it and who approved it: the Voids &amp; discounts report shows them.
 */
@Service
@RequiredArgsConstructor
public class DiscountService {

    private final OrderService orderService;
    private final OrderRepository orders;
    private final OrderLineRepository lines;
    private final OutletSettings settings;
    private final StaffOutlets staffOutlets;

    /** What was asked, worked out against the order: the percentage it comes to and what it is worth. */
    record Plan(SaleRequest.Kind kind, OrderLine line, BigDecimal pct, BigDecimal amount, BigDecimal worth, BigDecimal highestPct, String summary) {
    }

    /** {@code limit}: what the caller gives without asking (100 a manager, 0 nobody-without-asking). */
    BigDecimal limitFor(HospioUserDetails user, OutletSettings.Settings s) {
        if (OutletAccess.manager(user)) {
            return BigDecimal.valueOf(100);
        }
        boolean gives = s.discountRoles().stream().anyMatch(r -> {
            try {
                return user.has(com.hospio.common.enums.Role.valueOf(r), com.hospio.common.enums.AccessLevel.EDIT);
            } catch (IllegalArgumentException e) {
                return false;
            }
        });
        return gives && !user.isReadOnly() ? (s.discountLimitPct() == null ? BigDecimal.ZERO : s.discountLimitPct().max(BigDecimal.ZERO)) : BigDecimal.ZERO;
    }

    /** May work on this sale's bill at all: takes orders here (a waiter: their own), or a cashier. Assigned to the outlet. */
    void requireOnBill(HospioUserDetails user, Outlet outlet, Order o) {
        if (OutletAccess.cashier(user) || OutletAccess.manager(user)) {
            staffOutlets.require(user, outlet);
            return;
        }
        orderService.requireTake(user, outlet, o);
    }

    void requireOpen(Order o) {
        OrderService.requireLive(o);
        if (o.isSettled()) {
            throw new BadRequestException(o.getNumber() + " is already settled");
        }
        orderService.requireNotSplit(o, "change a discount");
    }

    Plan plan(Order o, List<OrderLine> all, Bill.DiscountRequest r) {
        boolean comp = Boolean.TRUE.equals(r.complimentary());
        boolean pctGiven = r.pct() != null && r.pct().signum() > 0;
        boolean amountGiven = r.amount() != null && r.amount().signum() > 0;
        if (!comp && pctGiven == amountGiven) {
            throw new BadRequestException("Enter the discount as a percentage or as an amount");
        }
        if (pctGiven && r.pct().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new BadRequestException("A discount is at most 100 %");
        }
        List<OrderLine> live = all.stream().filter(OrderLine::isLive).toList();
        if (live.isEmpty()) {
            throw new BadRequestException("There is nothing on " + o.getNumber() + " to discount");
        }
        List<Discounts.Line> now = live.stream().map(OrderService::discountLine).toList();
        if (r.lineId() != null && !r.lineId().isBlank()) {
            OrderLine l = OrderService.lineOf(all, r.lineId());
            if (!l.isLive()) {
                throw new BadRequestException(l.getName() + " was cancelled");
            }
            Discounts.Line d = OrderService.discountLine(l);
            if (d.base().signum() <= 0) {
                throw new BadRequestException(l.getName() + " is already free");
            }
            if (amountGiven && r.amount().compareTo(d.base()) > 0) {
                throw new BadRequestException("The discount is more than " + l.getName() + " costs (" + OrderService.naira(d.base()) + ")");
            }
            BigDecimal worth = comp ? d.base() : Discounts.lineDiscount(d.base(), r.pct(), r.amount());
            List<Discounts.Line> after = now.stream().map(x -> x.id().equals(l.getId()) ? new Discounts.Line(x.id(), x.gross(), x.happyHour(), worth) : x).toList();
            BigDecimal highest = Discounts.highestPct(after, Discounts.billShares(after, o.getBillDiscountPct(), o.getBillDiscountAmount()));
            String what = (l.getQuantity() > 1 ? l.getQuantity() + " × " : "") + l.getName();
            return new Plan(comp ? SaleRequest.Kind.COMP_LINE : SaleRequest.Kind.DISCOUNT_LINE, l, comp ? null : r.pct(), comp ? null : r.amount(), worth,
                    highest, comp ? what + " complimentary (" + OrderService.naira(worth) + ")"
                    : (pctGiven ? pct(r.pct()) + " off " : OrderService.naira(worth) + " off ") + what + (pctGiven ? " (" + OrderService.naira(worth) + ")" : ""));
        }
        BigDecimal left = now.stream().map(Discounts.Line::left).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (left.signum() <= 0) {
            throw new BadRequestException("Everything on " + o.getNumber() + " is already free");
        }
        if (comp) {
            List<Discounts.Line> after = now.stream().map(x -> new Discounts.Line(x.id(), x.gross(), x.happyHour(), x.base())).toList();
            BigDecimal base = now.stream().map(Discounts.Line::base).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new Plan(SaleRequest.Kind.COMP_BILL, null, null, null, base, Discounts.highestPct(after, Map.of()),
                    "The whole of " + o.getNumber() + " complimentary (" + OrderService.naira(base) + ")");
        }
        if (amountGiven && r.amount().compareTo(left) > 0) {
            throw new BadRequestException("The discount is more than the bill (" + OrderService.naira(left) + ")");
        }
        Map<String, BigDecimal> shares = Discounts.billShares(now, r.pct(), r.amount());
        BigDecimal worth = shares.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Plan(SaleRequest.Kind.DISCOUNT_BILL, null, r.pct(), r.amount(), worth, Discounts.highestPct(now, shares),
                (pctGiven ? pct(r.pct()) + " off the bill " : OrderService.naira(worth) + " off the bill ") + o.getNumber()
                        + (pctGiven ? " (" + OrderService.naira(worth) + ")" : ""));
    }

    static String pct(BigDecimal p) {
        return p.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + " %";
    }

    static String reason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("Choose the reason");
        }
        String r = reason.trim();
        return r.length() > 300 ? r.substring(0, 300) : r;
    }

    /**
     * Put the discount on the order now. {@code byId/byName}: who gave it (the requester when it was approved), {@code approvedBy}:
     * the manager who approved it, or null when nobody had to.
     */
    void apply(Order o, OutletSettings.Settings s, List<OrderLine> all, Plan plan, String reason, String byId, String byName, String approvedBy) {
        requireOpen(o);
        switch (plan.kind()) {
            case DISCOUNT_LINE, COMP_LINE -> give(plan.line(), plan.worth(), plan.kind() == SaleRequest.Kind.COMP_LINE, reason, byId, byName, approvedBy);
            case COMP_BILL -> {
                o.setBillDiscountPct(null);
                o.setBillDiscountAmount(null);
                for (OrderLine l : all) {
                    if (l.isLive()) {
                        // The bill's share comes off first (recalc), so the line is free by hand in full.
                        BigDecimal happy = l.happyHourDiscount();
                        l.setBillDiscount(Money.of(BigDecimal.ZERO));
                        l.setDiscount(Money.of(happy.add(l.getManualDiscount())));
                        give(l, OrderService.discountLine(l).base(), true, reason, byId, byName, approvedBy);
                    }
                }
                stamp(o, reason, byId, byName, approvedBy);
            }
            default -> {
                o.setBillDiscountPct(plan.pct());
                o.setBillDiscountAmount(plan.pct() != null ? null : plan.amount());
                stamp(o, reason, byId, byName, approvedBy);
            }
        }
        orderService.recalc(o, s);
        if (o.getPaidAmount().add(o.getRoomAmount()).compareTo(o.getTotal()) > 0) {
            // Rolled back: more was already taken than the bill would be worth.
            throw new BadRequestException(OrderService.naira(o.getPaidAmount().add(o.getRoomAmount())) + " is already paid on " + o.getNumber()
                    + ". The discount can't take the bill below that");
        }
        if (plan.kind() == SaleRequest.Kind.COMP_BILL && o.getTotal().signum() == 0 && o.getKind() != Order.Kind.TAB) {
            // Nothing left to pay: it is closed as complimentary, as closing a ₦0 order always did.
            List<OrderLine> fresh = lines.findByOrderIdOrderByCreatedAt(o.getId());
            orderService.settle(o, orderService.outlet(o.getOutletId()), "COMPLIMENTARY", fresh);
            o.setSettledByUser(byId);
            o.setSettledByName(byName);
            if (!OrderService.waitingElsewhere(fresh, o.getOutletId())) {
                orderService.close(o);
            }
        }
        orders.save(o);
    }

    private void give(OrderLine l, BigDecimal worth, boolean comp, String reason, String byId, String byName, String approvedBy) {
        BigDecimal happy = l.happyHourDiscount();
        l.setManualDiscount(Money.of(worth));
        l.setComplimentary(comp);
        l.setDiscount(Money.of(happy.add(worth).add(l.getBillDiscount())));
        l.setDiscountReason(reason);
        l.setDiscountBy(byId);
        l.setDiscountByName(byName);
        l.setDiscountApprovedByName(approvedBy);
        l.setDiscountAt(Instant.now());
        lines.save(l);
    }

    private static void stamp(Order o, String reason, String byId, String byName, String approvedBy) {
        o.setDiscountReason(reason);
        o.setDiscountBy(byId);
        o.setDiscountByName(byName);
        o.setDiscountApprovedByName(approvedBy);
    }

    /** Take a discount given by hand off again: one line's, or the bill's. Whoever may give one (or a manager). */
    @Transactional
    public void remove(String orderId, String lineId) {
        HospioUserDetails user = CurrentUser.require();
        OutletSettings.Settings s = settings.get(orderService.peek(orderId).getHotelId());
        Order o = orderService.lock(orderId);
        Outlet outlet = orderService.outlet(o.getOutletId());
        requireOnBill(user, outlet, o);
        requireOpen(o);
        List<OrderLine> all = new ArrayList<>(lines.findByOrderIdOrderByCreatedAt(orderId));
        if (lineId != null && !lineId.isBlank()) {
            OrderLine l = OrderService.lineOf(all, lineId);
            if (!Money.isPositive(l.getManualDiscount())) {
                throw new BadRequestException(l.getName() + " has no discount to remove");
            }
            mayRemove(user, s, l.getDiscountBy());
            BigDecimal happy = l.happyHourDiscount();
            l.setManualDiscount(Money.of(BigDecimal.ZERO));
            l.setComplimentary(false);
            l.setDiscount(Money.of(happy.add(l.getBillDiscount())));
            l.setDiscountReason(null);
            l.setDiscountBy(null);
            l.setDiscountByName(null);
            l.setDiscountApprovedByName(null);
            l.setDiscountAt(null);
            lines.save(l);
        } else {
            if (!o.hasBillDiscount()) {
                throw new BadRequestException(o.getNumber() + " has no discount on the bill");
            }
            mayRemove(user, s, o.getDiscountBy());
            o.setBillDiscountPct(null);
            o.setBillDiscountAmount(null);
            stamp(o, null, null, null, null);
        }
        orderService.recalc(o, s);
        orders.save(o);
    }

    private void mayRemove(HospioUserDetails user, OutletSettings.Settings s, String givenBy) {
        if (limitFor(user, s).signum() > 0 || (user.getUserId() != null && user.getUserId().equals(givenBy))) {
            return;
        }
        throw new ForbiddenException("Whoever gave the discount, a cashier or a manager takes it off");
    }
}
