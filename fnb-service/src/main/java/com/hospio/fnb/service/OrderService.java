package com.hospio.fnb.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ChargePostedEvent;
import com.hospio.common.kafka.events.OrderCreatedEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.fnb.client.ReservationClient;
import com.hospio.fnb.dto.OrderRequest;
import com.hospio.fnb.dto.OrderResponse;
import com.hospio.fnb.entity.DiningTable;
import com.hospio.fnb.entity.MenuItem;
import com.hospio.fnb.entity.Order;
import com.hospio.fnb.entity.OrderLine;
import com.hospio.fnb.entity.Outlet;
import com.hospio.fnb.repository.DiningTableRepository;
import com.hospio.fnb.repository.OrderLineRepository;
import com.hospio.fnb.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orders board, tickets, tabs and sales. A room-service order or bar sale charged to a room posts a
 * ChargePosted event that lands on the guest's folio; a sale paid at the POS is marked paid here.
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orders;
    private final OrderLineRepository lines;
    private final DiningTableRepository tables;
    private final MenuService menuService;
    private final ReservationClient reservations;
    private final EventPublisher events;

    @Transactional(readOnly = true)
    public List<OrderResponse> board(String outletId) {
        EnumSet<Order.Status> live = EnumSet.of(Order.Status.OPEN, Order.Status.SENT, Order.Status.READY, Order.Status.SERVED);
        List<Order> list = outletId == null ? orders.findByHotelIdAndStatusInOrderByCreatedAt(TenantContext.requireHotel(), live)
                : orders.findByOutletIdAndStatusInOrderByCreatedAt(outletId, live);
        return list.stream().map(o -> OrderResponse.of(o, lines.findByOrderIdOrderByCreatedAt(o.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public OrderResponse get(String id) {
        Order o = find(id);
        return OrderResponse.of(o, lines.findByOrderIdOrderByCreatedAt(o.getId()));
    }

    @Transactional
    public OrderResponse create(OrderRequest request) {
        HospioUserDetails user = CurrentUser.require();
        Outlet outlet = menuService.outlet(request.outletId());
        Order o = Order.builder().outletId(outlet.getId()).kind(request.kind() == null ? Order.Kind.DINE_IN : request.kind())
                .guestName(request.guestName()).notes(request.notes()).openedBy(user.getUserId()).openedByName(user.getFullName())
                .number(String.format("%s-%04d", outlet.getKind().name().charAt(0), orders.countByHotelId(TenantContext.requireHotel()) % 10000 + 1)).build();
        if (request.tableId() != null) {
            DiningTable t = tables.findById(request.tableId()).orElseThrow(() -> new ResourceNotFoundException("Table not found"));
            o.setTableId(t.getId());
            o.setTableNo(t.getNumber());
        }
        if (request.roomId() != null) {
            ReservationClient.ActiveStay stay = reservations.activeStay(request.roomId());
            o.setRoomId(request.roomId());
            o.setRoomNumber(stay.roomNumber());
            o.setBookingId(stay.bookingId());
            o.setGuestName(stay.guestName());
            if (o.getKind() == Order.Kind.DINE_IN) {
                o.setKind(Order.Kind.ROOM_SERVICE);
            }
        }
        orders.save(o);
        if (request.lines() != null) {
            request.lines().forEach(l -> addLineInternal(o, l));
        }
        recalc(o);
        events.publish(KafkaTopics.ORDER_CREATED, OrderCreatedEvent.builder().orderId(o.getId()).outletId(outlet.getId())
                .outletName(outlet.getName()).outletKind(outlet.getKind().name()).tableNo(o.getTableNo()).roomId(o.getRoomId())
                .total(o.getTotal()).itemCount(lines.findByOrderIdOrderByCreatedAt(o.getId()).size()).build());
        return get(o.getId());
    }

    @Transactional
    public OrderResponse addLine(String orderId, OrderRequest.LineRequest line) {
        Order o = find(orderId);
        if (o.getStatus() == Order.Status.CLOSED || o.getStatus() == Order.Status.CANCELLED) {
            throw new BadRequestException("This order is " + o.getStatus().name().toLowerCase());
        }
        addLineInternal(o, line);
        recalc(o);
        return get(orderId);
    }

    @Transactional
    public OrderResponse send(String orderId) {
        Order o = find(orderId);
        if (lines.findByOrderIdOrderByCreatedAt(orderId).isEmpty()) {
            throw new BadRequestException("Add something to the order first");
        }
        o.setStatus(Order.Status.SENT);
        o.setSentAt(Instant.now());
        lines.findByOrderIdOrderByCreatedAt(orderId).stream().filter(l -> l.getStatus() == OrderLine.Status.NEW).forEach(l -> {
            l.setStatus(OrderLine.Status.SENT);
            lines.save(l);
        });
        orders.save(o);
        return get(orderId);
    }

    @Transactional
    public OrderResponse setStatus(String orderId, Order.Status status) {
        Order o = find(orderId);
        if (status == Order.Status.CLOSED && !o.isSettled() && o.getTotal().signum() > 0) {
            throw new BadRequestException("Take payment or charge the room before closing");
        }
        o.setStatus(status);
        if (status == Order.Status.CLOSED || status == Order.Status.CANCELLED) {
            o.setClosedAt(Instant.now());
        }
        orders.save(o);
        return get(orderId);
    }

    /** Charge to a room: the stay must be current; the folio picks the charge up from the event. */
    @Transactional
    public OrderResponse chargeToRoom(String orderId, String roomId) {
        Order o = find(orderId);
        if (o.isSettled()) {
            throw new BadRequestException("This order is already settled");
        }
        String room = roomId == null ? o.getRoomId() : roomId;
        if (room == null) {
            throw new BadRequestException("Choose the room");
        }
        ReservationClient.ActiveStay stay = reservations.activeStay(room);
        o.setRoomId(room);
        o.setRoomNumber(stay.roomNumber());
        o.setBookingId(stay.bookingId());
        o.setGuestName(stay.guestName());
        o.setChargedToRoom(true);
        if (o.getStatus() == Order.Status.OPEN) {
            o.setStatus(Order.Status.SENT);
            o.setSentAt(Instant.now());
        }
        orders.save(o);
        Outlet outlet = menuService.outlet(o.getOutletId());
        events.publish(KafkaTopics.CHARGE_POSTED, ChargePostedEvent.builder().bookingId(stay.bookingId()).roomId(room)
                .roomNumber(stay.roomNumber()).sourceModule(outlet.getKind().name()).sourceRef("ORDER-" + o.getId())
                .description(outlet.getName() + " · " + o.getNumber()).amount(o.getSubtotal().subtract(o.getDiscount())).taxAmount(o.getTax())
                .category(outlet.getKind() == Outlet.Kind.BAR ? "BAR" : "FOOD").build());
        return get(orderId);
    }

    @Transactional
    public OrderResponse markPaid(String orderId, String paymentId) {
        Order o = find(orderId);
        o.setPaid(true);
        o.setPaymentId(paymentId);
        if (o.getKind() != Order.Kind.TAB) {
            o.setStatus(Order.Status.CLOSED);
            o.setClosedAt(Instant.now());
        }
        orders.save(o);
        return get(orderId);
    }

    @Transactional
    public OrderResponse moveTable(String orderId, String tableId) {
        Order o = find(orderId);
        DiningTable t = tables.findById(tableId).orElseThrow(() -> new ResourceNotFoundException("Table not found"));
        o.setTableId(t.getId());
        o.setTableNo(t.getNumber());
        orders.save(o);
        return get(orderId);
    }

    private void addLineInternal(Order o, OrderRequest.LineRequest l) {
        MenuItem m = menuService.item(l.menuItemId());
        if (!m.isAvailable() || !m.isActive()) {
            throw new BadRequestException(m.getName() + " is sold out");
        }
        lines.save(OrderLine.builder().orderId(o.getId()).menuItemId(m.getId()).name(m.getName())
                .quantity(l.quantity() == null || l.quantity() < 1 ? 1 : l.quantity()).unitPrice(m.getPrice()).taxRate(m.getTaxRate())
                .options(l.options()).notes(l.notes()).build());
    }

    private void recalc(Order o) {
        BigDecimal subtotal = BigDecimal.ZERO, tax = BigDecimal.ZERO;
        for (OrderLine l : lines.findByOrderIdOrderByCreatedAt(o.getId())) {
            if (l.getStatus() == OrderLine.Status.CANCELLED) {
                continue;
            }
            BigDecimal lineTotal = l.lineTotal();
            subtotal = subtotal.add(lineTotal);
            if (l.getTaxRate() != null) {
                tax = tax.add(Money.percent(lineTotal, l.getTaxRate()));
            }
        }
        o.setSubtotal(Money.of(subtotal));
        o.setTax(Money.of(tax));
        o.setTotal(Money.of(subtotal.add(tax).subtract(o.getDiscount())));
        orders.save(o);
    }

    private Order find(String id) {
        return orders.findById(id).orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    }
}
