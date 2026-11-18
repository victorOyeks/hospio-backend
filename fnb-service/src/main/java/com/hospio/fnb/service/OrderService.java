package com.hospio.fnb.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ConflictException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ChargePostedEvent;
import com.hospio.common.kafka.events.OrderCreatedEvent;
import com.hospio.common.kafka.events.OrderReadyEvent;
import com.hospio.common.kafka.events.OrderSettledEvent;
import com.hospio.common.kafka.events.TabLimitReachedEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.fnb.client.FolioClient;
import com.hospio.fnb.client.OutletSettings;
import com.hospio.fnb.client.PropertyClient;
import com.hospio.fnb.client.ReservationClient;
import com.hospio.fnb.dto.OrderRequest;
import com.hospio.fnb.dto.OrderResponse;
import com.hospio.fnb.entity.DiningTable;
import com.hospio.fnb.entity.MenuItem;
import com.hospio.fnb.entity.Order;
import com.hospio.fnb.entity.OrderLine;
import com.hospio.fnb.entity.OrderPayment;
import com.hospio.fnb.entity.Outlet;
import com.hospio.fnb.repository.DiningTableRepository;
import com.hospio.fnb.repository.HappyHourRepository;
import com.hospio.fnb.repository.MenuItemRepository;
import com.hospio.fnb.repository.OrderLineRepository;
import com.hospio.fnb.repository.OrderPaymentRepository;
import com.hospio.fnb.repository.OrderRepository;
import com.hospio.fnb.repository.OutletRepository;
import com.hospio.fnb.rules.Days;
import com.hospio.fnb.rules.HappyHours;
import com.hospio.fnb.rules.LineStatus;
import com.hospio.fnb.rules.OrderPricing;
import com.hospio.fnb.rules.OutletAccess;
import com.hospio.fnb.rules.Recipes;
import com.hospio.fnb.rules.StockSource;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orders board, tickets, tabs and sales. A room-service order or bar sale charged to a room posts a ChargePosted event that lands on
 * the guest's folio; a sale paid at the POS is marked paid when payment-service approves the payment (PAYMENT_APPROVED with the
 * order id). Settling an order, either way, tells inventory what was used (ORDER_SETTLED).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    static final EnumSet<Order.Status> LIVE = EnumSet.of(Order.Status.OPEN, Order.Status.SENT, Order.Status.READY, Order.Status.SERVED);

    private final OrderRepository orders;
    private final OrderLineRepository lines;
    private final OrderPaymentRepository orderPayments;
    private final DiningTableRepository tables;
    private final MenuItemRepository items;
    private final OutletRepository outlets;
    private final HappyHourRepository happyHours;
    private final ReservationClient reservations;
    private final FolioClient folios;
    private final PropertyClient properties;
    private final OutletSettings settings;
    private final EventPublisher events;
    private final com.hospio.fnb.repository.OrderSplitRepository splits;
    private final com.hospio.fnb.repository.OrderSplitPartRepository splitParts;
    private final com.hospio.fnb.repository.OrderTipRepository tips;
    private final StaffOutlets staffOutlets;

    private Supplier<ZonedDateTime> now = () -> ZonedDateTime.now(Days.LAGOS);

    /** For tests: the clock happy hours are judged against. */
    void useClock(Supplier<ZonedDateTime> clock) {
        this.now = clock;
    }

    // ---- reading -------------------------------------------------------------------------------------

    /**
     * The open board by default (OPEN, SENT, READY, SERVED); {@code status} narrows it. CLOSED or ALL with a {@code date} (today when
     * omitted) is that day's closed orders; CANCELLED is that day's cancelled ones. Staff see their own outlets; front office sees the
     * room-service orders.
     */
    @Transactional(readOnly = true)
    public List<OrderResponse> board(String outletId, String status, LocalDate date) {
        HospioUserDetails user = CurrentUser.require();
        String hotelId = TenantContext.requireHotel();
        Map<String, Outlet> byId = outlets.findByHotelIdOrderByName(hotelId).stream().collect(Collectors.toMap(Outlet::getId, o -> o));
        List<Order> list;
        String s = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        LocalDate day = date == null ? Days.today() : date;
        if (s.equals("CLOSED") || s.equals("ALL") || s.equals("CANCELLED")) {
            Order.Status st = s.equals("CANCELLED") ? Order.Status.CANCELLED : Order.Status.CLOSED;
            list = outletId == null
                    ? orders.findByHotelIdAndStatusAndClosedAtGreaterThanEqualAndClosedAtLessThanOrderByClosedAtDesc(hotelId, st, Days.start(day), Days.end(day))
                    : orders.findByOutletIdAndStatusAndClosedAtGreaterThanEqualAndClosedAtLessThanOrderByClosedAtDesc(outletId, st, Days.start(day), Days.end(day));
        } else {
            EnumSet<Order.Status> wanted = LIVE;
            if (!s.isEmpty()) {
                try {
                    wanted = EnumSet.of(Order.Status.valueOf(s));
                } catch (IllegalArgumentException e) {
                    throw new BadRequestException("Unknown status " + status);
                }
            }
            list = outletId == null ? orders.findByHotelIdAndStatusInOrderByCreatedAt(hotelId, wanted)
                    : orders.findByOutletIdAndStatusInOrderByCreatedAt(outletId, wanted);
        }
        // Someone kept to a list of outlets sees only those (StaffOutlets); everyone else every outlet their role shows.
        java.util.Set<String> kept = StaffOutlets.neverNarrowed(user) ? java.util.Set.of() : staffOutlets.listed(hotelId, user.getUserId());
        List<Order> visible = list.stream().filter(o -> byId.containsKey(o.getOutletId()) && canSee(user, byId.get(o.getOutletId()), o)
                && (kept.isEmpty() || kept.contains(o.getOutletId()) || (o.getKind() == Order.Kind.ROOM_SERVICE && user.has(com.hospio.common.enums.Role.FRONT_OFFICE)))).toList();
        List<OrderResponse> out = new ArrayList<>(withLines(visible));
        // An outlet that makes things for others (the kitchen) also sees those lines as tickets, on the live board only.
        if (outletId != null && byId.containsKey(outletId) && OutletAccess.sees(user, byId.get(outletId)) && !OutletAccess.ownOnly(user, byId.get(outletId))
                && (kept.isEmpty() || kept.contains(outletId))
                && !(s.equals("CLOSED") || s.equals("ALL") || s.equals("CANCELLED"))) {
            out.addAll(tickets(byId.get(outletId), byId, visible.stream().map(Order::getId).collect(Collectors.toSet())));
        }
        return out;
    }

    /**
     * Another outlet's live orders with lines this station makes, sent and not yet served: each as a ticket holding only those lines,
     * named with the outlet that sold it ({@code ticketFrom}), oldest first.
     */
    private List<OrderResponse> tickets(Outlet station, Map<String, Outlet> byId, java.util.Set<String> already) {
        Map<String, List<OrderLine>> byOrder = lines.findByPreparedAtAndStatusIn(station.getId(), EnumSet.of(OrderLine.Status.SENT, OrderLine.Status.READY))
                .stream().collect(Collectors.groupingBy(OrderLine::getOrderId, java.util.LinkedHashMap::new, Collectors.toList()));
        if (byOrder.isEmpty()) {
            return List.of();
        }
        List<OrderResponse> out = new ArrayList<>();
        for (Order o : orders.findAllById(byOrder.keySet())) {
            if (already.contains(o.getId()) || !LIVE.contains(o.getStatus()) || !byId.containsKey(o.getOutletId())) {
                continue;
            }
            List<OrderLine> mine = byOrder.get(o.getId()).stream().sorted(java.util.Comparator.comparing(OrderLine::getCreatedAt,
                    java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))).toList();
            out.add(OrderResponse.ticket(o, mine, byId.get(o.getOutletId()).getName()));
        }
        out.sort(java.util.Comparator.comparing(OrderResponse::createdAt, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        return out;
    }

    /**
     * The station (the kitchen) marks its lines on another outlet's order ready: every sent line it makes there. The order rolls up as
     * usual and the selling outlet hears it is ready when all its lines are.
     */
    @Transactional
    public OrderResponse stationReady(String orderId, String stationId) {
        Order o = lock(orderId);
        Outlet station = outlet(stationId);
        requireWorks(CurrentUser.require(), station);
        requireLive(o);
        List<OrderLine> all = lines.findByOrderIdOrderByCreatedAt(orderId);
        List<OrderLine> mine = all.stream().filter(l -> stationId.equals(l.getPreparedAt()) && l.getStatus() == OrderLine.Status.SENT).toList();
        if (mine.isEmpty()) {
            throw new BadRequestException("Nothing on this order is waiting at the " + station.getName());
        }
        mine.forEach(l -> {
            l.setStatus(OrderLine.Status.READY);
            lines.save(l);
        });
        Order.Status before = o.getStatus();
        o.setStatus(LineStatus.rollup(before, statuses(all)));
        orders.save(o);
        Outlet seller = outlet(o.getOutletId());
        events.publish(KafkaTopics.ORDER_READY, OrderReadyEvent.builder().orderId(o.getId()).orderNumber(o.getNumber()).outletId(seller.getId())
                .outletName(seller.getName() + " · from the " + station.getName()).outletKind(seller.getKind().name()).tableNo(o.getTableNo())
                .roomNumber(o.getRoomNumber()).openedBy(o.getOpenedBy()).build());
        return OrderResponse.ticket(o, all.stream().filter(l -> stationId.equals(l.getPreparedAt())).toList(), seller.getName());
    }

    @Transactional(readOnly = true)
    public OrderResponse get(String id) {
        Order o = find(id);
        HospioUserDetails user = CurrentUser.require();
        Outlet outlet = outlet(o.getOutletId());
        if (canSee(user, outlet, o) && !staffOutlets.allowed(user, outlet)
                && !(o.getKind() == Order.Kind.ROOM_SERVICE && user.has(com.hospio.common.enums.Role.FRONT_OFFICE))) {
            throw new ForbiddenException(StaffOutlets.refusal(outlet));
        }
        if (!canSee(user, outlet, o)) {
            throw new ForbiddenException(OutletAccess.ownOnly(user, outlet) ? o.getNumber() + " was opened by "
                    + (o.getOpenedByName() == null ? "someone else" : o.getOpenedByName()) + ". A waiter sees the tabs they opened"
                    : "This order belongs to another outlet");
        }
        return OrderResponse.of(o, lines.findByOrderIdOrderByCreatedAt(o.getId()));
    }

    /** Service-to-service read: no caller to check. */
    @Transactional(readOnly = true)
    public OrderResponse getInternal(String id) {
        Order o = find(id);
        return OrderResponse.of(o, lines.findByOrderIdOrderByCreatedAt(o.getId()));
    }

    // ---- taking orders -------------------------------------------------------------------------------

    @Transactional(noRollbackFor = BadRequestException.class)
    public OrderResponse create(OrderRequest request) {
        HospioUserDetails user = CurrentUser.require();
        String hotelId = TenantContext.requireHotel();
        Outlet outlet = outlets.lockById(request.outletId()).orElseThrow(() -> new ResourceNotFoundException("Outlet not found"));
        Order.Kind kind = request.kind() == null ? Order.Kind.DINE_IN : request.kind();
        if (request.roomId() != null && kind == Order.Kind.DINE_IN) {
            kind = Order.Kind.ROOM_SERVICE;
        }
        requireTake(user, outlet, kind);
        if (outlet.getStatus() == Outlet.Status.CLOSED) {
            throw new BadRequestException(outlet.getName() + " is closed");
        }
        OutletSettings.Settings s = settings.get(hotelId);
        if (kind == Order.Kind.TAB && !s.tabs()) {
            throw new BadRequestException("Tabs are switched off for this hotel");
        }
        if (kind == Order.Kind.ROOM_SERVICE && request.roomId() == null) {
            throw new BadRequestException("Choose the room");
        }
        if (kind == Order.Kind.TAB && s.tabNameRequired() && request.tableId() == null && request.roomId() == null
                && (request.guestName() == null || request.guestName().isBlank())) {
            throw new BadRequestException("Put the guest's name on the tab, or pick a table");
        }
        Order o = Order.builder().outletId(outlet.getId()).kind(kind).guestName(request.guestName()).notes(request.notes())
                .openedBy(user.getUserId()).openedByName(user.getFullName()).number(nextNumber(outlet)).build();
        if (request.tableId() != null) {
            DiningTable t = table(request.tableId(), outlet);
            o.setTableId(t.getId());
            o.setTableNo(t.getNumber());
        }
        if (request.roomId() != null) {
            ReservationClient.ActiveStay stay = stayOf(request.roomId(), null);
            o.setRoomId(request.roomId());
            o.setRoomNumber(stay.roomNumber());
            o.setBookingId(stay.bookingId());
            o.setGuestName(stay.guestName());
        }
        // Everything that can be refused is checked before anything is written.
        List<OrderLine> fresh = new ArrayList<>();
        if (request.lines() != null) {
            for (OrderRequest.LineRequest l : request.lines()) {
                fresh.add(newLine(outlet, l, s));
            }
        }
        checkTabLimit(user, o, outlet, List.of(), fresh, s);
        orders.save(o);
        for (OrderLine l : fresh) {
            l.setOrderId(o.getId());
            lines.save(l);
        }
        recalc(o, s);
        events.publish(KafkaTopics.ORDER_CREATED, OrderCreatedEvent.builder().orderId(o.getId()).orderNumber(o.getNumber()).outletId(outlet.getId())
                .outletName(outlet.getName()).outletKind(outlet.getKind().name()).tableNo(o.getTableNo()).roomId(o.getRoomId())
                .total(o.getTotal()).itemCount(fresh.size()).openedBy(user.getUserId()).outsideOutlet(OutletAccess.outside(user, outlet)).build());
        return view(o);
    }

    @Transactional(noRollbackFor = BadRequestException.class)
    public OrderResponse addLine(String orderId, OrderRequest.LineRequest line) {
        HospioUserDetails user = CurrentUser.require();
        // The hotel's settings come over HTTP, so before the lock; then the order is locked and checked. A line can't land on an order a
        // payment or charge-to-room is settling (it would never be billed), and two lines at once can't both pass the tab limit.
        OutletSettings.Settings s = settings.get(peek(orderId).getHotelId());
        Order o = lock(orderId);
        Outlet outlet = outlet(o.getOutletId());
        requireTake(user, outlet, o);
        requireLive(o);
        if (o.isSettled()) {
            throw new BadRequestException("This order is already settled. Start a new one");
        }
        requireNotSplit(o, "add to it");
        OrderLine fresh = newLine(outlet, line, s);
        List<OrderLine> existing = new ArrayList<>(lines.findByOrderIdOrderByCreatedAt(orderId));
        checkTabLimit(user, o, outlet, existing, List.of(fresh), s);
        fresh.setOrderId(orderId);
        lines.save(fresh);
        existing.add(fresh);
        recalc(o, s);
        o.setStatus(LineStatus.rollup(o.getStatus(), statuses(existing)));
        orders.save(o);
        return view(o);
    }

    @Transactional
    public OrderResponse send(String orderId) {
        HospioUserDetails user = CurrentUser.require();
        Order o = lock(orderId);
        requireTake(user, outlet(o.getOutletId()), o);
        requireLive(o);
        List<OrderLine> all = lines.findByOrderIdOrderByCreatedAt(orderId);
        if (all.stream().noneMatch(OrderLine::isLive)) {
            throw new BadRequestException("Add something to the order first");
        }
        for (OrderLine l : all) {
            if (l.getStatus() == OrderLine.Status.NEW) {
                l.setStatus(OrderLine.Status.SENT);
                lines.save(l);
            }
        }
        if (o.getSentAt() == null) {
            o.setSentAt(Instant.now());
        }
        o.setStatus(LineStatus.rollup(Order.Status.SENT, statuses(all)));
        orders.save(o);
        return view(o);
    }

    /** Order-level status for the kitchen or bar: sent, ready, served or closed. Cancelling has its own route (it needs a reason). */
    @Transactional
    public OrderResponse setStatus(String orderId, Order.Status status) {
        Order o = lock(orderId);
        Outlet outlet = outlet(o.getOutletId());
        if (status == Order.Status.SERVED) {
            requireServes(CurrentUser.require(), outlet, o);
        } else {
            requireWorks(CurrentUser.require(), outlet);
        }
        if (status == Order.Status.CANCELLED) {
            throw new BadRequestException("Cancel the order with a reason instead");
        }
        if (o.getStatus() == Order.Status.CLOSED || o.getStatus() == Order.Status.CANCELLED) {
            throw new BadRequestException("This order is " + o.getStatus().name().toLowerCase());
        }
        List<OrderLine> all = lines.findByOrderIdOrderByCreatedAt(orderId);
        Order.Status before = o.getStatus();
        if (status == Order.Status.CLOSED) {
            if (waitingElsewhere(all, o.getOutletId())) {
                throw new BadRequestException("The " + stationName(all, o.getOutletId()) + " is still making part of this order · serve it first");
            }
            if (!o.isSettled()) {
                if (o.getTotal().signum() > 0) {
                    throw new BadRequestException("Take payment or charge the room before closing");
                }
                settle(o, outlet, "COMPLIMENTARY", all);
            }
            close(o);
        } else {
            o.setStatus(status);
            if (status == Order.Status.READY || status == Order.Status.SERVED) {
                // Keep the dishes in step with the ticket.
                for (OrderLine l : all) {
                    // A line another outlet makes is marked ready there; it can be served here once it is ready.
                    boolean elsewhere = l.getPreparedAt() != null && !l.getPreparedAt().equals(o.getOutletId());
                    if (elsewhere ? (status == Order.Status.SERVED && l.getStatus() == OrderLine.Status.READY)
                            : (l.getStatus() == OrderLine.Status.SENT || (status == Order.Status.SERVED && l.getStatus() == OrderLine.Status.READY))) {
                        l.setStatus(status == Order.Status.READY ? OrderLine.Status.READY : OrderLine.Status.SERVED);
                        lines.save(l);
                    }
                }
                o.setStatus(LineStatus.rollup(status, statuses(all)));
                if (o.getStatus() == Order.Status.SERVED && o.isSettled()) {
                    close(o);
                }
            }
        }
        orders.save(o);
        announceReady(before, o, outlet);
        return view(o);
    }

    /** One dish or drink is ready or served; the order becomes READY when every live line is, SERVED when all are served. */
    @Transactional
    public OrderResponse setLineStatus(String orderId, String lineId, OrderLine.Status status) {
        Order o = lock(orderId);
        Outlet outlet = outlet(o.getOutletId());
        if (status != OrderLine.Status.READY && status != OrderLine.Status.SERVED) {
            throw new BadRequestException("A line is marked READY or SERVED");
        }
        List<OrderLine> all = lines.findByOrderIdOrderByCreatedAt(orderId);
        OrderLine l = lineOf(all, lineId);
        HospioUserDetails user = CurrentUser.require();
        boolean elsewhere = l.getPreparedAt() != null && !l.getPreparedAt().equals(o.getOutletId());
        if (elsewhere && status == OrderLine.Status.READY) {
            // Only the outlet that makes it (the kitchen) marks it ready.
            requireWorks(user, outlet(l.getPreparedAt()));
        } else {
            if (status == OrderLine.Status.SERVED) {
                requireServes(user, outlet, o);
            } else {
                requireWorks(user, outlet);
            }
            if (elsewhere && status == OrderLine.Status.SERVED && l.getStatus() == OrderLine.Status.SENT) {
                throw new BadRequestException("The " + outlet(l.getPreparedAt()).getName() + " hasn't marked " + l.getName() + " ready yet");
            }
        }
        requireLive(o);
        // The making outlet (the kitchen) gets its ticket back, as from its board: only its lines, no other outlet's.
        boolean ticket = elsewhere && status == OrderLine.Status.READY;
        switch (l.getStatus()) {
            case CANCELLED -> throw new BadRequestException(l.getName() + " was cancelled");
            case NEW -> throw new BadRequestException("Send the ticket first");
            case SERVED -> throw new BadRequestException(l.getName() + " is already served");
            case READY -> {
                if (status == OrderLine.Status.READY) {
                    return ticket ? stationTicket(o, all, l.getPreparedAt(), outlet) : view(o);
                }
            }
            default -> {
            }
        }
        l.setStatus(status);
        lines.save(l);
        Order.Status before = o.getStatus();
        o.setStatus(LineStatus.rollup(before, statuses(all)));
        if (o.getStatus() == Order.Status.SERVED && o.isSettled()) {
            close(o);
        }
        orders.save(o);
        announceReady(before, o, outlet);
        return ticket ? stationTicket(o, all, l.getPreparedAt(), outlet) : view(o);
    }

    private static OrderResponse stationTicket(Order o, List<OrderLine> all, String stationId, Outlet seller) {
        return OrderResponse.ticket(o, all.stream().filter(x -> stationId.equals(x.getPreparedAt())).toList(), seller.getName());
    }

    /**
     * Cancel one line with a reason. Before it is sent, anyone who took the order; after, a manager or Full access on the outlet's role.
     * When that was the last live line of an order (not a tab), the order is cancelled with it: nothing is left to make, serve or pay,
     * so it leaves the board instead of sitting there worth ₦0 with a Ready button (DEF-078 / DEF-085). A tab stays open for the next drink.
     */
    @Transactional
    public OrderResponse cancelLine(String orderId, String lineId, String reason) {
        HospioUserDetails user = CurrentUser.require();
        OutletSettings.Settings s = settings.get(peek(orderId).getHotelId());
        Order o = lock(orderId);
        Outlet outlet = outlet(o.getOutletId());
        List<OrderLine> all = lines.findByOrderIdOrderByCreatedAt(orderId);
        OrderLine l = lineOf(all, lineId);
        if (l.getStatus() == OrderLine.Status.NEW) {
            requireTake(user, outlet, o);
        } else if (!OutletAccess.full(user, outlet)) {
            throw new ForbiddenException("Cancelling something already sent needs a manager or Full access. Send a void request and a manager approves it");
        }
        return voidLine(o, outlet, s, all, l, reason, user.getUserId(), user.getFullName());
    }

    /**
     * The line comes off the order: by whoever may (above), or for a void request a manager approved ({@code SaleRequestService}),
     * then in the manager's name. Every rule about the order itself (live, not settled, not split, money already taken) is here.
     */
    OrderResponse voidLine(Order o, Outlet outlet, OutletSettings.Settings s, List<OrderLine> all, OrderLine l, String reason, String byId, String byName) {
        requireLive(o);
        if (o.isSettled()) {
            throw new BadRequestException("This order is already settled. A manager can adjust it");
        }
        requireNotSplit(o, "take an item off");
        if (l.getStatus() == OrderLine.Status.CANCELLED) {
            throw new BadRequestException(l.getName() + " is already cancelled");
        }
        l.setStatus(OrderLine.Status.CANCELLED);
        l.setCancelReason(reason.trim());
        l.setCancelledBy(byId);
        l.setCancelledByName(byName);
        lines.save(l);
        recalc(o, s);
        if (o.getPaidAmount().compareTo(o.getTotal()) > 0) {
            // Rolled back: the money already taken would be more than the order is worth.
            throw new BadRequestException(naira(o.getPaidAmount()) + " is already paid on " + o.getNumber() + ". A manager refunds before taking items off");
        }
        if (o.getKind() != Order.Kind.TAB && all.stream().noneMatch(OrderLine::isLive)) {
            String why = "Every item was cancelled · " + reason.trim();
            cancelled(o, byId, byName, why.length() > 300 ? why.substring(0, 300) : why);
            orders.save(o);
            return view(o);
        }
        Order.Status before = o.getStatus();
        o.setStatus(LineStatus.rollup(before, statuses(all)));
        orders.save(o);
        announceReady(before, o, outlet);
        return view(o);
    }

    private static void cancelled(Order o, String byId, String byName, String reason) {
        o.setStatus(Order.Status.CANCELLED);
        o.setSettledBy("CANCELLED");
        o.setCancelReason(reason);
        o.setClosedAt(Instant.now());
        o.setClosedBy(byId);
        o.setClosedByName(byName);
    }

    /** Cancel the order: staff while it is OPEN with nothing sent, a manager after. Refused once settled. */
    @Transactional
    public OrderResponse cancel(String orderId, String reason) {
        HospioUserDetails user = CurrentUser.require();
        Order o = lock(orderId);
        Outlet outlet = outlet(o.getOutletId());
        if (untouched(o)) {
            requireTake(user, outlet, o);
        } else if (!OutletAccess.manager(user)) {
            throw new ForbiddenException("Cancelling an order already sent needs a manager. Send a void request and a manager approves it");
        }
        return voidSale(o, reason, user.getUserId(), user.getFullName());
    }

    /** Nothing sent to the kitchen or bar yet: whoever took the order may still cancel it themselves. */
    boolean untouched(Order o) {
        return o.getStatus() == Order.Status.OPEN && lines.findByOrderIdOrderByCreatedAt(o.getId()).stream()
                .noneMatch(l -> l.getStatus() != OrderLine.Status.NEW && l.getStatus() != OrderLine.Status.CANCELLED);
    }

    /** The whole sale is cancelled (see {@link #voidLine}): a split with nothing paid on it is undone with it. */
    OrderResponse voidSale(Order o, String reason, String byId, String byName) {
        requireLive(o);
        if (o.isSettled()) {
            throw new BadRequestException("This order is already settled. It can't be cancelled");
        }
        if (Money.isPositive(o.getPaidAmount()) || Money.isPositive(o.getRoomAmount())) {
            throw new BadRequestException(naira(o.getPaidAmount().add(o.getRoomAmount())) + " is already paid on " + o.getNumber()
                    + ". Collect the rest, or a manager refunds it first");
        }
        splits.findFirstByOrderIdAndStatus(o.getId(), com.hospio.fnb.entity.OrderSplit.ACTIVE).ifPresent(sp -> {
            sp.setStatus(com.hospio.fnb.entity.OrderSplit.UNDONE);
            sp.setEndedAt(Instant.now());
            sp.setEndedByName(byName);
            splits.save(sp);
        });
        cancelled(o, byId, byName, reason.trim());
        orders.save(o);
        return view(o);
    }

    /**
     * Charge to a room: the hotel must allow it, the room must have a guest checked in and that stay an open bill; the folio picks the
     * charge up from the event. Settles the order (revenue arrives when the folio is paid).
     */
    @Transactional
    public OrderResponse chargeToRoom(String orderId, String roomId) {
        HospioUserDetails user = CurrentUser.require();
        // Settings, the stay and the folio are other services: asked first, from a peek that loads nothing. Then the order is locked and
        // every check on it made again, so a card payment and a charge to the room can't both settle it.
        OrderRepository.Peek p = peek(orderId);
        Outlet outlet = outlet(p.getOutletId());
        if (!OutletAccess.takes(user, outlet) && !OutletAccess.frontOffice(user) && !OutletAccess.cashier(user)) {
            throw new ForbiddenException("This order belongs to the " + outlet.getName());
        }
        if (!settings.get(p.getHotelId()).roomCharge()) {
            throw new BadRequestException("Charging to a room is switched off here (it needs rooms: a lounge, bar or restaurant has none)");
        }
        String room = roomId == null ? p.getRoomId() : roomId;
        if (room == null) {
            throw new BadRequestException("Choose the room");
        }
        ReservationClient.ActiveStay stay = stayOf(room, room.equals(p.getRoomId()) ? p.getRoomNumber() : null);
        if (!folios.hasOpenFolio(stay.bookingId())) {
            throw new BadRequestException("Room " + label(room, stay.roomNumber()) + " has no open bill");
        }
        Order o = lock(orderId);
        if (!OutletAccess.cashier(user) && !OutletAccess.frontOffice(user)) {
            requireOwn(user, outlet, o);
        }
        if (!OutletAccess.frontOffice(user)) {
            staffOutlets.require(user, outlet);
        }
        requireLive(o);
        if (o.isSettled()) {
            throw new BadRequestException("This order is already settled");
        }
        if (!Money.isPositive(o.getTotal()) || !Money.isPositive(o.balance())) {
            throw new BadRequestException("There is nothing to charge");
        }
        if (splits.findFirstByOrderIdAndStatus(orderId, com.hospio.fnb.entity.OrderSplit.ACTIVE).isPresent()) {
            throw new BadRequestException("This bill is split. Charge one of its parts to the room, or undo the split first");
        }
        if (roomId == null && !room.equals(o.getRoomId())) {
            throw new ConflictException("The order's room changed just now; reload and try again");
        }
        o.setRoomId(room);
        o.setRoomNumber(stay.roomNumber());
        o.setBookingId(stay.bookingId());
        o.setGuestName(stay.guestName());
        o.setChargedToRoom(true);
        List<OrderLine> all = lines.findByOrderIdOrderByCreatedAt(orderId);
        if (o.getStatus() == Order.Status.OPEN) {
            o.setStatus(Order.Status.SENT);
            o.setSentAt(Instant.now());
            all.stream().filter(l -> l.getStatus() == OrderLine.Status.NEW).forEach(l -> {
                l.setStatus(OrderLine.Status.SENT);
                lines.save(l);
            });
        }
        settle(o, outlet, "ROOM", all);
        // Settled, but kept open while another outlet (the kitchen) is still making part of it: its ticket stays on that board.
        if ((o.getKind() != Order.Kind.ROOM_SERVICE || o.getStatus() == Order.Status.SERVED) && !waitingElsewhere(all, o.getOutletId())) {
            close(o);
        }
        orders.save(o);
        // A part-paid order puts only what is left on the room; the tax goes with it in proportion.
        RoomCharge charge = roomCharge(o);
        events.publish(KafkaTopics.CHARGE_POSTED, ChargePostedEvent.builder().bookingId(stay.bookingId()).roomId(room)
                .roomNumber(stay.roomNumber()).sourceModule(outlet.getKind().name()).sourceRef("ORDER-" + o.getId())
                .description(outlet.getName() + " · " + o.getNumber() + (charge.partPaid() ? " (balance)" : "")).amount(charge.amount())
                .taxAmount(charge.tax()).category(outlet.getKind() == Outlet.Kind.BAR ? "BAR" : "FOOD").build());
        return view(o);
    }

    /** A payment payment-service approved for this order (PAYMENT_APPROVED with the order id). Once per payment id; closes the order when paid in full. */
    @Transactional
    public void applyPayment(String orderId, String paymentId, String method, BigDecimal amount) {
        applyPayment(orderId, paymentId, method, amount, null);
    }

    /** As above, keeping the terminal's or bank's {@code reference}; the taker is the event's actor (the tenant bound for it). */
    @Transactional
    public void applyPayment(String orderId, String paymentId, String method, BigDecimal amount, String reference) {
        applyPayment(orderId, paymentId, method, amount, reference, null, null);
    }

    /**
     * As above for a split bill and tips: {@code partId} is the part this pays (it is marked paid when its amount is reached) and
     * {@code tip} a tip inside {@code amount}. The tip is not a sale: only {@code amount - tip} counts on the order, the tip is kept
     * against whoever served (opened the sale).
     */
    @Transactional
    public void applyPayment(String orderId, String paymentId, String method, BigDecimal amount, String reference, String partId, BigDecimal tip) {
        // Locked first: a payment and a charge to the room (or the same payment delivered twice) settle the order one after the other.
        Order o = orders.lockById(orderId).orElse(null);
        if (o == null) {
            log.warn("Payment {} is for order {} which this hotel doesn't have", paymentId, orderId);
            return;
        }
        if (orderPayments.existsByPaymentId(paymentId)) {
            return;
        }
        Tenant actor = TenantContext.current().orElse(null);
        boolean person = actor != null && actor.actorId() != null && !"system".equals(actor.actorId());
        String kind = method == null ? "CASH" : method.toUpperCase(Locale.ROOT);
        BigDecimal tipped = tip == null || tip.signum() <= 0 ? BigDecimal.ZERO : Money.of(tip.min(amount));
        BigDecimal sale = Money.of(amount.subtract(tipped));
        com.hospio.fnb.entity.OrderSplitPart part = partId == null || partId.isBlank() ? null
                : splitParts.findById(partId).filter(x -> orderId.equals(x.getOrderId())).orElse(null);
        orderPayments.save(OrderPayment.builder().orderId(orderId).paymentId(paymentId).method(kind)
                .amount(sale).reference(reference == null || reference.isBlank() ? null : reference.trim())
                .takenBy(person ? actor.actorId() : null).takenByName(person ? actor.actorName() : null).partId(part == null ? null : part.getId()).build());
        if (tipped.signum() > 0 && !tips.existsByPaymentId(paymentId)) {
            tips.save(com.hospio.fnb.entity.OrderTip.builder().orderId(orderId).outletId(o.getOutletId()).paymentId(paymentId)
                    .partId(part == null ? null : part.getId()).amount(tipped).method(kind).staffId(o.getOpenedBy()).staffName(o.getOpenedByName())
                    .takenByName(person ? actor.actorName() : null).build());
        }
        if (part != null) {
            part.setPaidAmount(Money.of(part.getPaidAmount().add(sale)));
            part.setMethod(kind);
            if (part.getPaidAmount().compareTo(part.getAmount()) >= 0) {
                part.setStatus(com.hospio.fnb.entity.OrderSplitPart.PAID);
                part.setPaidAt(Instant.now());
                part.setPaidByName(person ? actor.actorName() : null);
            }
            splitParts.save(part);
        }
        o.setPaidAmount(Money.of(o.getPaidAmount().add(sale)));
        o.setPaymentId(paymentId);
        if (o.getStatus() == Order.Status.CANCELLED || o.isSettled()) {
            log.warn("Payment {} of {} arrived for order {} which is {}; recorded, not settled again", paymentId, amount, o.getNumber(),
                    o.getStatus() == Order.Status.CANCELLED ? "cancelled" : "already settled");
            orders.save(o);
            return;
        }
        settleWhenCovered(o, "PAYMENT");
        orders.save(o);
    }

    /**
     * Money taken plus what parts of a split bill put on rooms covers the total: the order is settled ({@code by}: PAYMENT or ROOM,
     * whichever finished it), its split is done, and it closes unless another outlet is still making part of it.
     */
    void settleWhenCovered(Order o, String by) {
        if (o.getPaidAmount().add(o.getRoomAmount()).compareTo(o.getTotal()) < 0) {
            return;
        }
        if (Money.isPositive(o.getRoomAmount())) {
            // Part of it is on a room: "charged to a room" with the money taken at the outlet as its paid part (SaleBucket).
            o.setChargedToRoom(true);
        } else {
            o.setPaid(true);
        }
        List<OrderLine> all = lines.findByOrderIdOrderByCreatedAt(o.getId());
        settle(o, outlet(o.getOutletId()), by, all);
        splits.findFirstByOrderIdAndStatus(o.getId(), com.hospio.fnb.entity.OrderSplit.ACTIVE).ifPresent(sp -> {
            sp.setStatus(com.hospio.fnb.entity.OrderSplit.DONE);
            sp.setEndedAt(Instant.now());
            splits.save(sp);
        });
        // Settled, but kept open while another outlet (the kitchen) is still making part of it; serving the rest closes it.
        if (!waitingElsewhere(all, o.getOutletId())) {
            close(o);
        }
    }

    /** For payment-service before it takes money for an order: the order's hotel, state and what is still due. */
    @Transactional(readOnly = true)
    public OrderDue due(String id) {
        return due(id, null, null);
    }

    /**
     * As above for one part of a split bill ({@code partId}: what that part still owes) and for the person paying ({@code payerId}:
     * refused at an outlet they aren't assigned to). {@code refusal}, when set, is the sentence payment-service answers with.
     */
    @Transactional(readOnly = true)
    public OrderDue due(String id, String partId, String payerId) {
        Order o = find(id);
        // The desk whose income this order is (BAR for a bar, KITCHEN for a kitchen or restaurant), whoever takes the money.
        Outlet outlet = outlets.findById(o.getOutletId()).orElse(null);
        String desk = outlet == null ? null : OutletAccess.roleFor(outlet).name();
        BigDecimal due = Money.of(o.balance());
        String refusal = null;
        String partLabel = null;
        com.hospio.fnb.entity.OrderSplit split = splits.findFirstByOrderIdAndStatus(id, com.hospio.fnb.entity.OrderSplit.ACTIVE).orElse(null);
        if (split != null && !o.isSettled()) {
            List<com.hospio.fnb.entity.OrderSplitPart> ps = splitParts.findBySplitIdOrderByPosition(split.getId());
            com.hospio.fnb.entity.OrderSplitPart part = partId == null ? null : ps.stream().filter(x -> x.getId().equals(partId)).findFirst().orElse(null);
            if (part == null) {
                refusal = o.getNumber() + " is split into " + ps.size() + " parts. Take the payment for one of its parts";
            } else if (com.hospio.fnb.entity.OrderSplitPart.PAID.equals(part.getStatus())) {
                refusal = part.getLabel() + " of " + o.getNumber() + " is already paid";
            } else {
                due = Money.of(part.due().min(o.balance()));
                partLabel = part.getLabel() + " of " + ps.size();
            }
        } else if (partId != null && !partId.isBlank() && !o.isSettled()) {
            refusal = "The split on " + o.getNumber() + " was undone. Reload the bill and take the payment again";
        }
        if (refusal == null && payerId != null && outlet != null && !staffOutlets.allowed(payerId, outlet)) {
            refusal = "You aren't assigned to " + outlet.getName() + ". A manager can add it to your outlets (Staff → Edit)";
        }
        return new OrderDue(o.getId(), o.getHotelId(), o.getNumber(), o.getStatus().name(), o.isSettled(), o.getTotal(), o.getPaidAmount(),
                due, desk, o.getOpenedBy(), refusal, partLabel, o.getOpenedByName());
    }

    /**
     * What payment-service reads before taking money for an order; {@code desk} is left out when the outlet is gone. {@code refusal}:
     * why no payment can be taken as asked (a split bill paid without naming a part, a part already paid, an outlet the payer isn't
     * assigned to). {@code partLabel}: "Part 2 of 3", for the receipt.
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record OrderDue(String id, String hotelId, String number, String status, boolean settled, BigDecimal total, BigDecimal paidAmount,
                           BigDecimal due, String desk, String openedBy, String refusal, String partLabel, String openedByName) {
    }

    /** The internal "paid" route: the whole balance was paid by {@code method} (CASH when not said). */
    @Transactional
    public OrderResponse markPaid(String orderId, String paymentId, String method) {
        Order o = lock(orderId);
        BigDecimal due = o.balance();
        applyPayment(orderId, paymentId, method, due.signum() > 0 ? due : BigDecimal.ZERO);
        return getInternal(orderId);
    }

    @Transactional
    public OrderResponse moveTable(String orderId, String tableId) {
        Order o = lock(orderId);
        Outlet outlet = outlet(o.getOutletId());
        requireServes(CurrentUser.require(), outlet, o);
        requireLive(o);
        DiningTable t = table(tableId, outlet);
        o.setTableId(t.getId());
        o.setTableNo(t.getNumber());
        orders.save(o);
        return view(o);
    }

    // ---- open orders (night audit) ---------------------------------------------------------------------

    /** Open tabs and unsettled orders created by the end of {@code date}, per outlet. Service-to-service. */
    @Transactional(readOnly = true)
    public com.hospio.fnb.dto.OpenOrdersSummary openOrders(String hotelId, LocalDate date) {
        Instant until = Days.end(date == null ? Days.today() : date);
        List<Outlet> all = outlets.findByHotelIdOrderByName(hotelId);
        List<Order> open = orders.findByHotelIdAndStatusInOrderByCreatedAt(hotelId, LIVE).stream()
                .filter(o -> !o.isSettled() && o.getCreatedAt() != null && o.getCreatedAt().isBefore(until)).toList();
        List<com.hospio.fnb.dto.OpenOrdersSummary.OutletOpen> rows = new ArrayList<>();
        int tabs = 0, unsettled = 0;
        for (Outlet outlet : all) {
            List<Order> mine = open.stream().filter(o -> outlet.getId().equals(o.getOutletId())).toList();
            if (mine.isEmpty()) {
                continue;
            }
            int t = (int) mine.stream().filter(o -> o.getKind() == Order.Kind.TAB).count();
            rows.add(new com.hospio.fnb.dto.OpenOrdersSummary.OutletOpen(outlet.getId(), outlet.getName(), outlet.getKind(), t, mine.size() - t,
                    mine.stream().map(o -> new com.hospio.fnb.dto.OpenOrdersSummary.OpenOrder(o.getId(), o.getNumber(), o.getKind(), o.getTableNo(),
                            o.getRoomNumber(), o.getTotal())).toList()));
            tabs += t;
            unsettled += mine.size() - t;
        }
        return new com.hospio.fnb.dto.OpenOrdersSummary(tabs, unsettled, rows);
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    /** The line as it would be sold now: current price, happy-hour discount, tax rate (the item's, else the hotel's VAT). */
    OrderLine newLine(Outlet outlet, OrderRequest.LineRequest l, OutletSettings.Settings s) {
        MenuItem m = items.findById(l.menuItemId()).orElseThrow(() -> new ResourceNotFoundException("Menu item not found"));
        if (!m.getOutletId().equals(outlet.getId())) {
            throw new BadRequestException(m.getName() + " isn't on the " + outlet.getName() + " menu");
        }
        if (!m.isAvailable() || !m.isActive()) {
            throw new BadRequestException(m.getName() + " is sold out");
        }
        if (l.quantity() != null && l.quantity() < 1) {
            throw new BadRequestException("The quantity must be at least 1");
        }
        int qty = l.quantity() == null ? 1 : l.quantity();
        // A choice can cost more (Hot +500): the portion is priced with what the guest chose.
        BigDecimal unit = Money.of(m.getPrice().add(com.hospio.fnb.rules.MenuChoices.extra(m.getName(), m.getOptions(), l.options())).max(BigDecimal.ZERO));
        BigDecimal gross = Money.of(unit.multiply(BigDecimal.valueOf(qty)));
        BigDecimal pct = HappyHours.activePct(happyHours.findByOutletIdAndActiveTrue(outlet.getId()), now.get());
        return OrderLine.builder().menuItemId(m.getId()).name(m.getName()).quantity(qty).unitPrice(unit)
                .taxRate(m.getTaxRate() != null ? m.getTaxRate() : s.vatPct()).discount(OrderPricing.happyHourDiscount(gross, pct))
                .options(l.options()).notes(l.notes()).preparedAt(m.getPreparedAt()).build();
    }

    /** A tab can't grow past the hotel's tab limit unless a manager is adding to it. The manager is told when a drink is refused. */
    void checkTabLimit(HospioUserDetails user, Order o, Outlet outlet, List<OrderLine> existing, List<OrderLine> fresh, OutletSettings.Settings s) {
        if (o.getKind() != Order.Kind.TAB || s.tabLimit() == null || s.tabLimit().signum() <= 0 || OutletAccess.manager(user)) {
            return;
        }
        List<OrderLine> all = new ArrayList<>(existing);
        all.addAll(fresh);
        BigDecimal projected = OrderPricing.price(inputs(all), s.pricesIncludeTax()).total();
        if (projected.compareTo(s.tabLimit()) > 0) {
            events.publish(KafkaTopics.TAB_LIMIT_REACHED, TabLimitReachedEvent.builder().orderId(o.getId()).orderNumber(o.getNumber())
                    .outletName(outlet.getName()).tableNo(o.getTableNo()).limit(Money.of(s.tabLimit())).total(projected).build());
            throw new BadRequestException("Tab limit " + naira(s.tabLimit()) + " reached · settle or ask the manager");
        }
    }

    void recalc(Order o, OutletSettings.Settings s) {
        List<OrderLine> all = lines.findByOrderIdOrderByCreatedAt(o.getId());
        shareBillDiscount(o, all);
        OrderPricing.Totals t = OrderPricing.price(inputs(all), s.pricesIncludeTax());
        o.setSubtotal(t.subtotal());
        o.setTax(t.tax());
        o.setDiscount(t.discount());
        // Hospio's fee passed to the guest, on the net (no tax): added on top of the bill.
        BigDecimal sc = com.hospio.common.billing.GuestServiceCharge.on(t.total().subtract(t.tax()), s.serviceChargePct());
        o.setServiceCharge(sc);
        o.setTotal(Money.of(t.total().add(sc)));
        orders.save(o);
    }

    /**
     * A discount on the whole bill is kept on its lines: each live line's share goes into its {@code billDiscount} and its
     * {@code discount} (happy hour + by hand + this share), worked out again whenever the lines change.
     */
    private void shareBillDiscount(Order o, List<OrderLine> all) {
        List<OrderLine> live = all.stream().filter(OrderLine::isLive).toList();
        Map<String, BigDecimal> shares = com.hospio.fnb.rules.Discounts.billShares(live.stream().map(OrderService::discountLine).toList(),
                o.getBillDiscountPct(), o.getBillDiscountAmount());
        for (OrderLine l : live) {
            BigDecimal share = Money.of(shares.getOrDefault(l.getId(), BigDecimal.ZERO));
            if (share.compareTo(l.getBillDiscount()) != 0) {
                BigDecimal happy = l.happyHourDiscount();
                l.setBillDiscount(share);
                l.setDiscount(Money.of(happy.add(l.getManualDiscount()).add(share)));
                lines.save(l);
            }
        }
    }

    static com.hospio.fnb.rules.Discounts.Line discountLine(OrderLine l) {
        return new com.hospio.fnb.rules.Discounts.Line(l.getId(), Money.of(l.lineTotal()), l.happyHourDiscount(), l.getManualDiscount());
    }

    /** While a bill is split nothing on it changes: the parts were worked out from it. */
    void requireNotSplit(Order o, String toDoWhat) {
        if (o.getId() != null && splits.findFirstByOrderIdAndStatus(o.getId(), com.hospio.fnb.entity.OrderSplit.ACTIVE).isPresent()) {
            throw new BadRequestException(o.getNumber() + " is split. Undo the split to " + toDoWhat
                    + " (a split can be undone until one of its parts is paid)");
        }
    }

    private static List<OrderPricing.Input> inputs(List<OrderLine> all) {
        return all.stream().filter(OrderLine::isLive).map(l -> new OrderPricing.Input(l.getUnitPrice(), l.getQuantity(), l.getDiscount(), l.getTaxRate())).toList();
    }

    static List<OrderLine.Status> statuses(List<OrderLine> all) {
        return all.stream().map(OrderLine::getStatus).toList();
    }

    /** Marks the order settled and tells inventory what it used. */
    void settle(Order o, Outlet outlet, String by, List<OrderLine> all) {
        o.setSettledBy(by);
        o.setSettledAt(Instant.now());
        Tenant t = TenantContext.current().orElse(null);
        if (t != null) {
            o.setSettledByUser(t.actorId());
            o.setSettledByName(t.actorName());
        }
        Map<String, MenuItem> menu = new HashMap<>();
        Map<String, java.util.Optional<Outlet>> outletCache = new HashMap<>();
        Map<String, List<MenuItem>> menus = new HashMap<>();
        List<OrderSettledEvent.Line> sold = new ArrayList<>();
        for (OrderLine l : all) {
            if (!l.isLive()) {
                continue;
            }
            MenuItem m = menu.computeIfAbsent(l.getMenuItemId(), id -> items.findById(id).orElse(null));
            // A dish made at another outlet draws from that outlet's store, with the recipe it keeps there (StockSource).
            StockSource.Source src = StockSource.of(m, l.getPreparedAt(), outlet, id -> outletCache.computeIfAbsent(id, outlets::findById),
                    id -> menus.computeIfAbsent(id, items::findByOutletIdOrderByCategoryAscSortOrderAscNameAsc));
            List<OrderSettledEvent.Ingredient> recipe = src.recipe().stream().map(r -> new OrderSettledEvent.Ingredient(r.stockItemId(), r.quantity())).toList();
            sold.add(new OrderSettledEvent.Line(l.getMenuItemId(), l.getName(), l.getQuantity(), recipe, src.storeId()));
        }
        events.publish(KafkaTopics.ORDER_SETTLED, OrderSettledEvent.builder().orderId(o.getId()).orderNumber(o.getNumber()).outletId(outlet.getId())
                .storeId(outlet.getStoreId()).settledBy(by).lines(sold).build());
    }

    /** What a charge to the room posts: the balance (total less what was paid), split into the part without tax and its tax. */
    record RoomCharge(BigDecimal amount, BigDecimal tax, boolean partPaid) {
    }

    /**
     * What goes on the room's folio: the net and its tax, without the guest service charge (the folio adds its own line for it,
     * so the guest never pays it twice). A part-paid order puts only what is left, tax and service charge in proportion.
     */
    static RoomCharge roomCharge(Order o) {
        BigDecimal net = o.getSubtotal().subtract(o.getDiscount());
        if (!Money.isPositive(o.getPaidAmount())) {
            return new RoomCharge(Money.of(net), Money.of(o.getTax()), false);
        }
        BigDecimal balance = Money.of(o.balance());
        BigDecimal tax = o.getTotal().signum() == 0 ? BigDecimal.ZERO
                : o.getTax().multiply(balance).divide(o.getTotal(), 2, java.math.RoundingMode.HALF_UP);
        BigDecimal sc = o.getTotal().signum() == 0 || o.getServiceCharge() == null ? BigDecimal.ZERO
                : o.getServiceCharge().multiply(balance).divide(o.getTotal(), 2, java.math.RoundingMode.HALF_UP);
        return new RoomCharge(Money.of(balance.subtract(tax).subtract(sc)), Money.of(tax), true);
    }

    void close(Order o) {
        o.setStatus(Order.Status.CLOSED);
        o.setClosedAt(Instant.now());
        Tenant t = TenantContext.current().orElse(null);
        if (t != null) {
            o.setClosedBy(t.actorId());
            o.setClosedByName(t.actorName());
        }
    }

    private void announceReady(Order.Status before, Order o, Outlet outlet) {
        if (before != Order.Status.READY && o.getStatus() == Order.Status.READY) {
            events.publish(KafkaTopics.ORDER_READY, OrderReadyEvent.builder().orderId(o.getId()).orderNumber(o.getNumber()).outletId(outlet.getId())
                    .outletName(outlet.getName()).outletKind(outlet.getKind().name()).tableNo(o.getTableNo()).roomNumber(o.getRoomNumber())
                    .openedBy(o.getOpenedBy()).build());
        }
    }

    /** Day's running number per outlet: B-0001, K-0001, starting again each Lagos day. The outlet row is locked while this runs. */
    private String nextNumber(Outlet outlet) {
        LocalDate today = Days.today();
        long n = orders.countByOutletIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(outlet.getId(), Days.start(today), Days.end(today));
        return String.format("%s-%04d", outlet.getKind().name().charAt(0), n % 10000 + 1);
    }

    private ReservationClient.ActiveStay stayOf(String roomId, String knownNumber) {
        return reservations.findActiveStay(roomId).orElseThrow(() -> new BadRequestException("Nobody is checked in to room " + label(roomId, knownNumber)));
    }

    private String label(String roomId, String knownNumber) {
        if (knownNumber != null && !knownNumber.isBlank()) {
            return knownNumber;
        }
        String n = properties.roomNumber(roomId);
        return n == null ? roomId : n;
    }

    static String naira(BigDecimal amount) {
        return "₦" + NumberFormat.getIntegerInstance(Locale.UK).format(amount.setScale(0, java.math.RoundingMode.HALF_UP));
    }

    private void requireTake(HospioUserDetails user, Outlet outlet, Order.Kind kind) {
        if (kind == Order.Kind.ROOM_SERVICE && OutletAccess.frontOffice(user)) {
            return;
        }
        if (OutletAccess.takes(user, outlet)) {
            staffOutlets.require(user, outlet);
            return;
        }
        throw new ForbiddenException(OutletAccess.cashier(user) ? "A cashier takes payments. Orders are taken by a waiter or the " + outlet.getName()
                : "This order belongs to the " + outlet.getName());
    }

    /** As {@link #requireTake(HospioUserDetails, Outlet, Order.Kind)} on an order that exists: a waiter works the orders they opened. */
    void requireTake(HospioUserDetails user, Outlet outlet, Order o) {
        requireTake(user, outlet, o.getKind());
        requireOwn(user, outlet, o);
    }

    /** Serving and moving a table: whoever works the outlet, or the waiter whose order it is. */
    private void requireServes(HospioUserDetails user, Outlet outlet, Order o) {
        if (!OutletAccess.takes(user, outlet)) {
            throw new ForbiddenException("This order belongs to the " + outlet.getName());
        }
        staffOutlets.require(user, outlet);
        requireOwn(user, outlet, o);
    }

    /** A waiter (below Full access, with no other way into the outlet) only touches what they opened. */
    static void requireOwn(HospioUserDetails user, Outlet outlet, Order o) {
        if (OutletAccess.ownOnly(user, outlet) && !OutletAccess.own(user, o.getOpenedBy())) {
            String who = o.getOpenedByName() == null ? "someone else" : o.getOpenedByName();
            throw new ForbiddenException(o.getNumber() + " was opened by " + who + ". Only they, a head waiter or a manager can work on it");
        }
    }

    private void requireWorks(HospioUserDetails user, Outlet outlet) {
        if (!OutletAccess.works(user, outlet)) {
            throw new ForbiddenException("This order belongs to the " + outlet.getName());
        }
        staffOutlets.require(user, outlet);
    }

    private static boolean canSee(HospioUserDetails user, Outlet outlet, Order o) {
        if (OutletAccess.ownOnly(user, outlet) && !OutletAccess.own(user, o.getOpenedBy())) {
            return o.getKind() == Order.Kind.ROOM_SERVICE && user.has(com.hospio.common.enums.Role.FRONT_OFFICE);
        }
        return OutletAccess.sees(user, outlet) || (o.getKind() == Order.Kind.ROOM_SERVICE && user.has(com.hospio.common.enums.Role.FRONT_OFFICE));
    }

    /** Lines another outlet makes (the kitchen's pepper soup on a patio order) that haven't been served yet. */
    static boolean waitingElsewhere(List<OrderLine> all, String sellingOutletId) {
        return all.stream().anyMatch(l -> l.getPreparedAt() != null && !l.getPreparedAt().equals(sellingOutletId) && l.isLive()
                && l.getStatus() != OrderLine.Status.SERVED);
    }

    /** The station's name for a message ("the Kitchen"). */
    private String stationName(List<OrderLine> all, String sellingOutletId) {
        return all.stream().filter(l -> l.getPreparedAt() != null && !l.getPreparedAt().equals(sellingOutletId)).findFirst()
                .map(l -> outlets.findById(l.getPreparedAt()).map(Outlet::getName).orElse("other outlet")).orElse("other outlet");
    }

    static void requireLive(Order o) {
        if (o.getStatus() == Order.Status.CLOSED || o.getStatus() == Order.Status.CANCELLED) {
            throw new BadRequestException("This order is " + o.getStatus().name().toLowerCase());
        }
    }

    static OrderLine lineOf(List<OrderLine> all, String lineId) {
        return all.stream().filter(l -> l.getId().equals(lineId)).findFirst().orElseThrow(() -> new ResourceNotFoundException("Line not found"));
    }

    private DiningTable table(String tableId, Outlet outlet) {
        DiningTable t = tables.findById(tableId).orElseThrow(() -> new ResourceNotFoundException("Table not found"));
        if (!t.getOutletId().equals(outlet.getId())) {
            throw new BadRequestException("Table " + t.getNumber() + " isn't at the " + outlet.getName());
        }
        return t;
    }

    Outlet outlet(String id) {
        return outlets.findById(id).orElseThrow(() -> new ResourceNotFoundException("Outlet not found"));
    }

    /** The order with its row locked for the rest of the transaction: taken before any status, settled or limit check. */
    Order lock(String id) {
        return orders.lockById(id).orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    }

    OrderRepository.Peek peek(String id) {
        return orders.peek(id).orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    }

    private Order find(String id) {
        return orders.findById(id).orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    }

    OrderResponse view(Order o) {
        return OrderResponse.of(o, lines.findByOrderIdOrderByCreatedAt(o.getId()));
    }

    private List<OrderResponse> withLines(List<Order> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<String, List<OrderLine>> byOrder = lines.findByOrderIdIn(list.stream().map(Order::getId).toList()).stream()
                .sorted(java.util.Comparator.comparing(OrderLine::getCreatedAt, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .collect(Collectors.groupingBy(OrderLine::getOrderId));
        return list.stream().map(o -> OrderResponse.of(o, byOrder.getOrDefault(o.getId(), List.of()))).toList();
    }
}
