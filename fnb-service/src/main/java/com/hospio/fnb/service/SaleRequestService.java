package com.hospio.fnb.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ApprovalRequestedEvent;
import com.hospio.common.kafka.events.NoticeEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.fnb.client.OutletSettings;
import com.hospio.fnb.dto.Bill;
import com.hospio.fnb.entity.Order;
import com.hospio.fnb.entity.OrderLine;
import com.hospio.fnb.entity.Outlet;
import com.hospio.fnb.entity.SaleRequest;
import com.hospio.fnb.repository.OrderLineRepository;
import com.hospio.fnb.repository.OrderRepository;
import com.hospio.fnb.repository.OutletRepository;
import com.hospio.fnb.repository.SaleRequestRepository;
import com.hospio.fnb.rules.Discounts;
import com.hospio.fnb.rules.OutletAccess;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Requests from the floor (docs/plan-venue-type.md, phase 4): a void of something already sent (or of a whole sale) and a discount
 * over the person's limit wait for a manager. Each is a platform approval (kind VOID, DISCOUNT or COMPLIMENTARY, module FNB), so it
 * shows in the manager's and owner's approvals and "Needs your action", is decided there (or on the device with the manager's own
 * PIN), and the decision comes back on APPROVAL_DECIDED: approved requests are applied here in the requester's name with the
 * approver recorded; the requester is told either way.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SaleRequestService {

    private final OrderService orderService;
    private final DiscountService discounts;
    private final SaleRequestApplier applier;
    private final OrderRepository orders;
    private final OrderLineRepository lines;
    private final OutletRepository outlets;
    private final SaleRequestRepository requests;
    private final OutletSettings settings;
    private final EventPublisher events;

    /** A discount or complimentary item: given now when the caller may, else sent to a manager. Returns the request when one was made. */
    @Transactional
    public SaleRequest discount(String orderId, Bill.DiscountRequest request) {
        HospioUserDetails user = CurrentUser.require();
        OutletSettings.Settings s = settings.get(orderService.peek(orderId).getHotelId());
        Order o = orderService.lock(orderId);
        Outlet outlet = orderService.outlet(o.getOutletId());
        discounts.requireOnBill(user, outlet, o);
        discounts.requireOpen(o);
        String reason = DiscountService.reason(request.reason());
        List<OrderLine> all = new ArrayList<>(lines.findByOrderIdOrderByCreatedAt(orderId));
        DiscountService.Plan plan = discounts.plan(o, all, request);
        BigDecimal limit = discounts.limitFor(user, s);
        boolean comp = plan.kind() == SaleRequest.Kind.COMP_LINE || plan.kind() == SaleRequest.Kind.COMP_BILL;
        if (OutletAccess.manager(user) || (!comp && limit.signum() > 0 && Discounts.within(plan.highestPct(), limit))) {
            discounts.apply(o, s, all, plan, reason, user.getUserId(), user.getFullName(), null);
            return null;
        }
        return ask(o, outlet, user, plan.kind(), plan.line() == null ? null : plan.line().getId(), plan.pct(), plan.amount(), plan.worth(),
                plan.summary(), reason, comp ? "COMPLIMENTARY" : "DISCOUNT");
    }

    /** A void the caller may not do alone: an item already sent ({@code lineId}), or the whole sale. */
    @Transactional
    public SaleRequest requestVoid(String orderId, Bill.VoidRequest request) {
        HospioUserDetails user = CurrentUser.require();
        Order o = orderService.lock(orderId);
        Outlet outlet = orderService.outlet(o.getOutletId());
        discounts.requireOnBill(user, outlet, o);
        OrderService.requireLive(o);
        if (o.isSettled()) {
            throw new BadRequestException(o.getNumber() + " is already settled. A manager refunds it from Payments");
        }
        String reason = DiscountService.reason(request.reason());
        List<OrderLine> all = lines.findByOrderIdOrderByCreatedAt(orderId);
        boolean waiterOrOutlet = OutletAccess.takes(user, outlet);
        if (request.lineId() != null && !request.lineId().isBlank()) {
            OrderLine l = OrderService.lineOf(all, request.lineId());
            if (!l.isLive()) {
                throw new BadRequestException(l.getName() + " is already cancelled");
            }
            if (OutletAccess.full(user, outlet) || (l.getStatus() == OrderLine.Status.NEW && waiterOrOutlet)) {
                throw new BadRequestException("You can take " + l.getName() + " off yourself: no request is needed");
            }
            orderService.requireNotSplit(o, "take an item off");
            BigDecimal worth = Money.of(l.lineTotal().subtract(l.getDiscount()));
            return ask(o, outlet, user, SaleRequest.Kind.VOID_LINE, l.getId(), null, null, worth,
                    "Void " + (l.getQuantity() > 1 ? l.getQuantity() + " × " : "") + l.getName() + " (" + OrderService.naira(worth) + ")", reason, "VOID");
        }
        if (OutletAccess.manager(user) || (orderService.untouched(o) && waiterOrOutlet)) {
            throw new BadRequestException("You can cancel " + o.getNumber() + " yourself: no request is needed");
        }
        if (Money.isPositive(o.getPaidAmount()) || Money.isPositive(o.getRoomAmount())) {
            throw new BadRequestException(OrderService.naira(o.getPaidAmount().add(o.getRoomAmount())) + " is already paid on " + o.getNumber()
                    + ". Collect the rest, or a manager refunds it first");
        }
        return ask(o, outlet, user, SaleRequest.Kind.VOID_SALE, null, null, null, o.getTotal(),
                "Void the whole sale (" + OrderService.naira(o.getTotal()) + ")", reason, "VOID");
    }

    private SaleRequest ask(Order o, Outlet outlet, HospioUserDetails user, SaleRequest.Kind kind, String lineId, BigDecimal pct, BigDecimal amount,
                            BigDecimal worth, String summary, String reason, String approvalKind) {
        boolean discountFamily = kind != SaleRequest.Kind.VOID_LINE && kind != SaleRequest.Kind.VOID_SALE;
        for (SaleRequest waiting : requests.findByOrderIdAndStatus(o.getId(), SaleRequest.PENDING)) {
            boolean sameFamily = discountFamily == (waiting.getKind() != SaleRequest.Kind.VOID_LINE && waiting.getKind() != SaleRequest.Kind.VOID_SALE);
            if (sameFamily && java.util.Objects.equals(waiting.getLineId(), lineId)) {
                throw new BadRequestException("\"" + waiting.getSummary() + "\" is already waiting for a manager on " + o.getNumber()
                        + ". Ask again once they decide");
            }
        }
        String approvalId = UUID.randomUUID().toString();
        SaleRequest r = requests.save(SaleRequest.builder().orderId(o.getId()).outletId(outlet.getId()).lineId(lineId).kind(kind).pct(pct)
                .amount(amount).worth(Money.of(worth)).summary(summary).reason(reason).approvalId(approvalId).requestedBy(user.getUserId())
                .requestedByName(user.getFullName()).build());
        String where = o.getNumber() + " at " + outlet.getName() + (o.getTableNo() == null ? "" : ", table " + o.getTableNo())
                + (o.getGuestName() == null || o.getGuestName().isBlank() ? "" : " (" + o.getGuestName() + ")");
        String description = summary + " on " + where + ": " + reason;
        events.publish(KafkaTopics.APPROVAL_REQUESTED, ApprovalRequestedEvent.builder().approvalId(approvalId).sourceModule("FNB")
                .sourceRef(o.getId()).kind(approvalKind).amount(Money.of(worth)).requesterId(user.getUserId()).requesterName(user.getFullName())
                .approverLevel("MANAGER").description(description.length() > 480 ? description.substring(0, 480) : description).build());
        // The manager is told through the approval; the owner sees it in their bell too and may decide it.
        events.publish(KafkaTopics.NOTICE, NoticeEvent.builder().kind("APPROVAL").title("Approval needed: " + approvalKind.toLowerCase())
                .body(user.getFullName() + " asks: " + description).link("/approvals/" + approvalId).roles(List.of("OWNER")).build());
        return r;
    }

    /** The manager's (or owner's) decision came back. Once per request; a question back leaves it waiting. */
    @Transactional
    public void decided(String approvalId, String decision, String byId, String byName, String note) {
        SaleRequest r = requests.findByApprovalId(approvalId).orElse(null);
        if (r == null || !SaleRequest.PENDING.equals(r.getStatus())) {
            return;
        }
        if (!"APPROVED".equals(decision) && !"DECLINED".equals(decision)) {
            return;
        }
        r.setDecidedBy(byId);
        r.setDecidedByName(byName);
        r.setDecidedAt(Instant.now());
        r.setDecisionNote(note == null || note.isBlank() ? null : (note.length() > 500 ? note.substring(0, 500) : note));
        String number = orders.findById(r.getOrderId()).map(Order::getNumber).orElse("the sale");
        String title;
        String body;
        if ("DECLINED".equals(decision)) {
            r.setStatus(SaleRequest.DECLINED);
            title = "Declined: " + r.getSummary();
            body = (byName == null ? "A manager" : byName) + " declined it on " + number + (r.getDecisionNote() == null ? "" : ": " + r.getDecisionNote());
        } else {
            try {
                applier.apply(r, byName);
                r.setStatus(SaleRequest.APPROVED);
                title = "Approved: " + r.getSummary();
                body = (byName == null ? "A manager" : byName) + " approved it on " + number + ". It is done";
            } catch (RuntimeException e) {
                log.info("Approved request {} on order {} was not applied: {}", r.getId(), r.getOrderId(), e.getMessage());
                r.setStatus(SaleRequest.NOT_APPLIED);
                String why = e.getMessage() == null ? "The sale changed in the meantime" : e.getMessage();
                r.setDecisionNote(why.length() > 500 ? why.substring(0, 500) : why);
                title = "Approved, but not done: " + r.getSummary();
                body = (byName == null ? "A manager" : byName) + " approved it on " + number + ", but nothing was changed: " + why;
            }
        }
        requests.save(r);
        if (r.getRequestedBy() != null) {
            events.publish(KafkaTopics.NOTICE, NoticeEvent.builder().kind("APPROVAL_DECIDED").title(title).body(body)
                    .link("/dashboard?order=" + r.getOrderId()).userIds(List.of(r.getRequestedBy())).build());
        }
    }

    /**
     * Requests at this location, newest first: {@code status} PENDING (the default) or ALL; {@code mine} the caller's own. Managers
     * and owners see every request; everyone else their own.
     */
    @Transactional(readOnly = true)
    public List<Bill.Request> list(String status, Boolean mine) {
        HospioUserDetails user = CurrentUser.require();
        String hotelId = TenantContext.requireHotel();
        boolean everything = (user.isPlatform() || user.isManagerOrOwner()) && !Boolean.TRUE.equals(mine);
        boolean all = "ALL".equalsIgnoreCase(status);
        List<SaleRequest> rows = everything
                ? (all ? requests.findTop100ByHotelIdOrderByCreatedAtDesc(hotelId) : requests.findByHotelIdAndStatusOrderByCreatedAtDesc(hotelId, SaleRequest.PENDING))
                : requests.findTop50ByHotelIdAndRequestedByOrderByCreatedAtDesc(hotelId, user.getUserId()).stream()
                        .filter(r -> all || SaleRequest.PENDING.equals(r.getStatus())).toList();
        return views(rows);
    }

    List<Bill.Request> views(List<SaleRequest> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, Order> byId = orders.findAllById(rows.stream().map(SaleRequest::getOrderId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Order::getId, o -> o));
        Map<String, String> outletNames = outlets.findAllById(rows.stream().map(SaleRequest::getOutletId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Outlet::getId, Outlet::getName));
        return rows.stream().map(r -> new Bill.Request(r.getId(), r.getApprovalId(), r.getKind().name(), r.getOrderId(),
                byId.containsKey(r.getOrderId()) ? byId.get(r.getOrderId()).getNumber() : null, outletNames.get(r.getOutletId()), r.getLineId(), r.getSummary(),
                r.getPct(), r.getAmount(), r.getWorth(), r.getReason(), r.getStatus(), r.getRequestedBy(), r.getRequestedByName(), r.getCreatedAt(),
                r.getDecidedByName(), r.getDecisionNote(), r.getDecidedAt())).toList();
    }

    /** A request by id, for whoever asked or a manager. */
    @Transactional(readOnly = true)
    public Bill.Request get(String id) {
        HospioUserDetails user = CurrentUser.require();
        SaleRequest r = requests.findById(id).or(() -> requests.findByApprovalId(id))
                .orElseThrow(() -> new com.hospio.common.exception.ResourceNotFoundException("Request not found"));
        if (!user.isPlatform() && !user.isManagerOrOwner() && !java.util.Objects.equals(user.getUserId(), r.getRequestedBy())) {
            throw new ForbiddenException("That request was made by someone else");
        }
        return views(List.of(r)).get(0);
    }
}
