package com.hospio.fnb.service;

import com.hospio.common.exception.BadRequestException;
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
        List<Order> visible = list.stream().filter(o -> byId.containsKey(o.getOutletId()) && canSee(user, byId.get(o.getOutletId()), o)).toList();
        return withLines(visible);
    }

    @Transactional(readOnly = true)
    public OrderResponse get(String id) {
        Order o = find(id);
        HospioUserDetails user = CurrentUser.require();
        if (!canSee(user, outlet(o.getOutletId()), o)) {
            throw new ForbiddenException("This order belongs to another outlet");
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
        Order o = find(orderId);
        Outlet outlet = outlet(o.getOutletId());
        requireTake(user, outlet, o.getKind());
        requireLive(o);
        if (o.isSettled()) {
            throw new BadRequestException("This order is already settled. Start a new one");
        }
        OutletSettings.Settings s = settings.get(o.getHotelId());
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
        Order o = find(orderId);
        requireTake(user, outlet(o.getOutletId()), o.getKind());
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
        Order o = find(orderId);
        Outlet outlet = outlet(o.getOutletId());
        requireWorks(CurrentUser.require(), outlet);
        if (status == Order.Status.CANCELLED) {
            throw new BadRequestException("Cancel the order with a reason instead");
        }
        if (o.getStatus() == Order.Status.CLOSED || o.getStatus() == Order.Status.CANCELLED) {
            throw new BadRequestException("This order is " + o.getStatus().name().toLowerCase());
        }
        List<OrderLine> all = lines.findByOrderIdOrderByCreatedAt(orderId);
        Order.Status before = o.getStatus();
        if (status == Order.Status.CLOSED) {
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
                    if (l.getStatus() == OrderLine.Status.SENT || (status == Order.Status.SERVED && l.getStatus() == OrderLine.Status.READY)) {
                        l.setStatus(status == Order.Status.READY ? OrderLine.Status.READY : OrderLine.Status.SERVED);
                        lines.save(l);
                    }
                }
                if (status == Order.Status.SERVED && o.isSettled()) {
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
        Order o = find(orderId);
        Outlet outlet = outlet(o.getOutletId());
        requireWorks(CurrentUser.require(), outlet);
        if (status != OrderLine.Status.READY && status != OrderLine.Status.SERVED) {
            throw new BadRequestException("A line is marked READY or SERVED");
        }
        requireLive(o);
        List<OrderLine> all = lines.findByOrderIdOrderByCreatedAt(orderId);
        OrderLine l = lineOf(all, lineId);
        switch (l.getStatus()) {
            case CANCELLED -> throw new BadRequestException(l.getName() + " was cancelled");
            case NEW -> throw new BadRequestException("Send the ticket first");
            case SERVED -> throw new BadRequestException(l.getName() + " is already served");
            case READY -> {
                if (status == OrderLine.Status.READY) {
                    return view(o);
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
        return view(o);
    }

    /** Cancel one line with a reason. Before it is sent, anyone who took the order; after, a manager or Full access on the outlet's role. */
    @Transactional
    public OrderResponse cancelLine(String orderId, String lineId, String reason) {
        HospioUserDetails user = CurrentUser.require();
        Order o = find(orderId);
        Outlet outlet = outlet(o.getOutletId());
        requireLive(o);
        if (o.isSettled()) {
            throw new BadRequestException("This order is already settled. A manager can adjust it");
        }
        List<OrderLine> all = lines.findByOrderIdOrderByCreatedAt(orderId);
        OrderLine l = lineOf(all, lineId);
        if (l.getStatus() == OrderLine.Status.CANCELLED) {
            throw new BadRequestException(l.getName() + " is already cancelled");
        }
        if (l.getStatus() == OrderLine.Status.NEW) {
            requireTake(user, outlet, o.getKind());
        } else if (!OutletAccess.full(user, outlet)) {
            throw new ForbiddenException("Cancelling something already sent needs a manager or Full access");
        }
        l.setStatus(OrderLine.Status.CANCELLED);
        l.setCancelReason(reason.trim());
        l.setCancelledBy(user.getUserId());
        lines.save(l);
        recalc(o, settings.get(o.getHotelId()));
        Order.Status before = o.getStatus();
        o.setStatus(LineStatus.rollup(before, statuses(all)));
        orders.save(o);
        announceReady(before, o, outlet);
        return view(o);
    }

    /** Cancel the order: staff while it is OPEN with nothing sent, a manager after. Refused once settled. */
    @Transactional
    public OrderResponse cancel(String orderId, String reason) {
        HospioUserDetails user = CurrentUser.require();
        Order o = find(orderId);
        Outlet outlet = outlet(o.getOutletId());
        requireLive(o);
        if (o.isSettled()) {
            throw new BadRequestException("This order is already settled. It can't be cancelled");
        }
        boolean untouched = o.getStatus() == Order.Status.OPEN && lines.findByOrderIdOrderByCreatedAt(orderId).stream()
                .noneMatch(l -> l.getStatus() != OrderLine.Status.NEW && l.getStatus() != OrderLine.Status.CANCELLED);
        if (untouched) {
            requireTake(user, outlet, o.getKind());
        } else if (!OutletAccess.manager(user)) {
            throw new ForbiddenException("Cancelling an order already sent needs a manager");
        }
        o.setStatus(Order.Status.CANCELLED);
        o.setSettledBy("CANCELLED");
        o.setCancelReason(reason.trim());
        o.setClosedAt(Instant.now());
        o.setClosedBy(user.getUserId());
        o.setClosedByName(user.getFullName());
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
        Order o = find(orderId);
        Outlet outlet = outlet(o.getOutletId());
        if (!OutletAccess.works(user, outlet) && !OutletAccess.frontOffice(user)) {
            throw new ForbiddenException("This order belongs to the " + outlet.getName());
        }
        if (!settings.get(o.getHotelId()).roomCharge()) {
            throw new BadRequestException("Charging to a room is switched off for this hotel");
        }
        requireLive(o);
        if (o.isSettled()) {
            throw new BadRequestException("This order is already settled");
        }
        if (!Money.isPositive(o.getTotal())) {
            throw new BadRequestException("There is nothing to charge");
        }
        String room = roomId == null ? o.getRoomId() : roomId;
        if (room == null) {
            throw new BadRequestException("Choose the room");
        }
        ReservationClient.ActiveStay stay = stayOf(room, room.equals(o.getRoomId()) ? o.getRoomNumber() : null);
        if (!folios.hasOpenFolio(stay.bookingId())) {
            throw new BadRequestException("Room " + label(room, stay.roomNumber()) + " has no open bill");
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
        if (o.getKind() != Order.Kind.ROOM_SERVICE || o.getStatus() == Order.Status.SERVED) {
            close(o);
        }
        orders.save(o);
        events.publish(KafkaTopics.CHARGE_POSTED, ChargePostedEvent.builder().bookingId(stay.bookingId()).roomId(room)
                .roomNumber(stay.roomNumber()).sourceModule(outlet.getKind().name()).sourceRef("ORDER-" + o.getId())
                .description(outlet.getName() + " · " + o.getNumber()).amount(o.getSubtotal().subtract(o.getDiscount())).taxAmount(o.getTax())
                .category(outlet.getKind() == Outlet.Kind.BAR ? "BAR" : "FOOD").build());
        return view(o);
    }

    /** A payment payment-service approved for this order (PAYMENT_APPROVED with the order id). Once per payment id; closes the order when paid in full. */
    @Transactional
    public void applyPayment(String orderId, String paymentId, String method, BigDecimal amount) {
        if (orderPayments.existsByPaymentId(paymentId)) {
            return;
        }
        Order o = orders.findById(orderId).orElse(null);
        if (o == null) {
            log.warn("Payment {} is for order {} which this hotel doesn't have", paymentId, orderId);
            return;
        }
        orderPayments.save(OrderPayment.builder().orderId(orderId).paymentId(paymentId).method(method == null ? "CASH" : method.toUpperCase(Locale.ROOT))
                .amount(Money.of(amount)).build());
        o.setPaidAmount(Money.of(o.getPaidAmount().add(amount)));
        o.setPaymentId(paymentId);
        if (o.getStatus() == Order.Status.CANCELLED || o.isSettled()) {
            log.warn("Payment {} of {} arrived for order {} which is {}; recorded, not settled again", paymentId, amount, o.getNumber(),
                    o.getStatus() == Order.Status.CANCELLED ? "cancelled" : "already settled");
            orders.save(o);
            return;
        }
        if (o.getPaidAmount().compareTo(o.getTotal()) >= 0) {
            o.setPaid(true);
            settle(o, outlet(o.getOutletId()), "PAYMENT", lines.findByOrderIdOrderByCreatedAt(orderId));
            close(o);
        }
        orders.save(o);
    }

    /** The internal "paid" route: the whole balance was paid by {@code method} (CASH when not said). */
    @Transactional
    public OrderResponse markPaid(String orderId, String paymentId, String method) {
        Order o = find(orderId);
        BigDecimal due = o.getTotal().subtract(o.getPaidAmount());
        applyPayment(orderId, paymentId, method, due.signum() > 0 ? due : BigDecimal.ZERO);
        return getInternal(orderId);
    }

    @Transactional
    public OrderResponse moveTable(String orderId, String tableId) {
        Order o = find(orderId);
        Outlet outlet = outlet(o.getOutletId());
        requireWorks(CurrentUser.require(), outlet);
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
        int qty = l.quantity() == null || l.quantity() < 1 ? 1 : l.quantity();
        BigDecimal gross = Money.of(m.getPrice().multiply(BigDecimal.valueOf(qty)));
        BigDecimal pct = HappyHours.activePct(happyHours.findByOutletIdAndActiveTrue(outlet.getId()), now.get());
        return OrderLine.builder().menuItemId(m.getId()).name(m.getName()).quantity(qty).unitPrice(m.getPrice())
                .taxRate(m.getTaxRate() != null ? m.getTaxRate() : s.vatPct()).discount(OrderPricing.happyHourDiscount(gross, pct))
                .options(l.options()).notes(l.notes()).build();
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

    private void recalc(Order o, OutletSettings.Settings s) {
        OrderPricing.Totals t = OrderPricing.price(inputs(lines.findByOrderIdOrderByCreatedAt(o.getId())), s.pricesIncludeTax());
        o.setSubtotal(t.subtotal());
        o.setTax(t.tax());
        o.setDiscount(t.discount());
        o.setTotal(t.total());
        orders.save(o);
    }

    private static List<OrderPricing.Input> inputs(List<OrderLine> all) {
        return all.stream().filter(OrderLine::isLive).map(l -> new OrderPricing.Input(l.getUnitPrice(), l.getQuantity(), l.getDiscount(), l.getTaxRate())).toList();
    }

    private static List<OrderLine.Status> statuses(List<OrderLine> all) {
        return all.stream().map(OrderLine::getStatus).toList();
    }

    /** Marks the order settled and tells inventory what it used. */
    private void settle(Order o, Outlet outlet, String by, List<OrderLine> all) {
        o.setSettledBy(by);
        o.setSettledAt(Instant.now());
        Map<String, MenuItem> menu = new HashMap<>();
        List<OrderSettledEvent.Line> sold = new ArrayList<>();
        for (OrderLine l : all) {
            if (!l.isLive()) {
                continue;
            }
            MenuItem m = menu.computeIfAbsent(l.getMenuItemId(), id -> items.findById(id).orElse(null));
            List<OrderSettledEvent.Ingredient> recipe = m == null ? List.of()
                    : Recipes.read(m.getRecipeJson()).stream().map(r -> new OrderSettledEvent.Ingredient(r.stockItemId(), r.quantity())).toList();
            sold.add(new OrderSettledEvent.Line(l.getMenuItemId(), l.getName(), l.getQuantity(), recipe));
        }
        events.publish(KafkaTopics.ORDER_SETTLED, OrderSettledEvent.builder().orderId(o.getId()).orderNumber(o.getNumber()).outletId(outlet.getId())
                .storeId(outlet.getStoreId()).settledBy(by).lines(sold).build());
    }

    private void close(Order o) {
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

    private static String naira(BigDecimal amount) {
        return "₦" + NumberFormat.getIntegerInstance(Locale.UK).format(amount.setScale(0, java.math.RoundingMode.HALF_UP));
    }

    private void requireTake(HospioUserDetails user, Outlet outlet, Order.Kind kind) {
        if (OutletAccess.works(user, outlet) || (kind == Order.Kind.ROOM_SERVICE && OutletAccess.frontOffice(user))) {
            return;
        }
        throw new ForbiddenException("This order belongs to the " + outlet.getName());
    }

    private static void requireWorks(HospioUserDetails user, Outlet outlet) {
        if (!OutletAccess.works(user, outlet)) {
            throw new ForbiddenException("This order belongs to the " + outlet.getName());
        }
    }

    private static boolean canSee(HospioUserDetails user, Outlet outlet, Order o) {
        return OutletAccess.sees(user, outlet) || (o.getKind() == Order.Kind.ROOM_SERVICE && user.has(com.hospio.common.enums.Role.FRONT_OFFICE));
    }

    private static void requireLive(Order o) {
        if (o.getStatus() == Order.Status.CLOSED || o.getStatus() == Order.Status.CANCELLED) {
            throw new BadRequestException("This order is " + o.getStatus().name().toLowerCase());
        }
    }

    private static OrderLine lineOf(List<OrderLine> all, String lineId) {
        return all.stream().filter(l -> l.getId().equals(lineId)).findFirst().orElseThrow(() -> new ResourceNotFoundException("Line not found"));
    }

    private DiningTable table(String tableId, Outlet outlet) {
        DiningTable t = tables.findById(tableId).orElseThrow(() -> new ResourceNotFoundException("Table not found"));
        if (!t.getOutletId().equals(outlet.getId())) {
            throw new BadRequestException("Table " + t.getNumber() + " isn't at the " + outlet.getName());
        }
        return t;
    }

    private Outlet outlet(String id) {
        return outlets.findById(id).orElseThrow(() -> new ResourceNotFoundException("Outlet not found"));
    }

    private Order find(String id) {
        return orders.findById(id).orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    }

    private OrderResponse view(Order o) {
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
