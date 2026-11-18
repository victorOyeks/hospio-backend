package com.hospio.fnb.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ChargePostedEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.util.Money;
import com.hospio.fnb.client.FolioClient;
import com.hospio.fnb.client.OutletSettings;
import com.hospio.fnb.client.ReservationClient;
import com.hospio.fnb.dto.Bill;
import com.hospio.fnb.entity.Order;
import com.hospio.fnb.entity.OrderLine;
import com.hospio.fnb.entity.OrderSplit;
import com.hospio.fnb.entity.OrderSplitPart;
import com.hospio.fnb.entity.Outlet;
import com.hospio.fnb.repository.OrderLineRepository;
import com.hospio.fnb.repository.OrderRepository;
import com.hospio.fnb.repository.OrderSplitPartRepository;
import com.hospio.fnb.repository.OrderSplitRepository;
import com.hospio.fnb.rules.BillSplit;
import com.hospio.fnb.rules.OrderPricing;
import com.hospio.fnb.rules.OutletAccess;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Splitting a bill (docs/plan-venue-type.md, phase 4): equally between people, by item, or by amount. What is divided is what the
 * order still owes, so the parts always add up to it exactly ({@link BillSplit}). Each part is then paid on its own: a payment
 * names the part (payment-service passes {@code partId}; {@code OrderService.applyPayment} marks it), or the part goes on a room.
 * The split is undone only while no part has been paid; while it stands the order's lines and discounts don't change.
 */
@Service
@RequiredArgsConstructor
public class SplitService {

    static final int MAX_PARTS = 20;
    private static final TypeReference<List<Bill.PartItem>> ITEMS = new TypeReference<>() {
    };

    private final OrderService orderService;
    private final OrderRepository orders;
    private final OrderLineRepository lines;
    private final OrderSplitRepository splits;
    private final OrderSplitPartRepository parts;
    private final OutletSettings settings;
    private final StaffOutlets staffOutlets;
    private final ReservationClient reservations;
    private final FolioClient folios;
    private final EventPublisher events;
    private final ObjectMapper mapper;

    /** May take the money for this sale: the outlet's own people, a cashier, a manager, or its waiter where waiters take payment. */
    boolean takesMoney(HospioUserDetails user, Outlet outlet, Order o, OutletSettings.Settings s) {
        if (user.isReadOnly() || !staffOutlets.allowed(user, outlet)) {
            return false;
        }
        if (OutletAccess.works(user, outlet) || OutletAccess.cashier(user)) {
            return true;
        }
        return OutletAccess.waiter(user) && s.waitersTakePayment()
                && (OutletAccess.own(user, o.getOpenedBy()) || user.has(com.hospio.common.enums.Role.WAITER, com.hospio.common.enums.AccessLevel.FULL));
    }

    private void requireTakesMoney(HospioUserDetails user, Outlet outlet, Order o, OutletSettings.Settings s) {
        if (!takesMoney(user, outlet, o, s)) {
            throw new ForbiddenException(!staffOutlets.allowed(user, outlet) ? StaffOutlets.refusal(outlet)
                    : OutletAccess.waiter(user) ? "Waiters don't take payment here: the cashier splits and settles the bill"
                    : "Splitting a bill is for whoever takes its payment at " + outlet.getName());
        }
    }

    @Transactional
    public void split(String orderId, Bill.SplitRequest request) {
        HospioUserDetails user = CurrentUser.require();
        OutletSettings.Settings s = settings.get(orderService.peek(orderId).getHotelId());
        Order o = orderService.lock(orderId);
        Outlet outlet = orderService.outlet(o.getOutletId());
        requireTakesMoney(user, outlet, o, s);
        OrderService.requireLive(o);
        if (o.isSettled()) {
            throw new BadRequestException(o.getNumber() + " is already settled");
        }
        BigDecimal owed = Money.of(o.balance());
        if (!Money.isPositive(owed)) {
            throw new BadRequestException("There is nothing left to pay on " + o.getNumber());
        }
        if (splits.findFirstByOrderIdAndStatus(orderId, OrderSplit.ACTIVE).isPresent()) {
            throw new BadRequestException(o.getNumber() + " is already split. Undo that split first");
        }
        String mode = request.mode() == null ? "" : request.mode().trim().toUpperCase(Locale.ROOT);
        List<String> labels = new ArrayList<>();
        List<BigDecimal> amounts;
        List<List<Bill.PartItem>> items = new ArrayList<>();
        switch (mode) {
            case "EQUAL" -> {
                int people = request.people() == null ? 0 : request.people();
                if (people < 2 || people > MAX_PARTS) {
                    throw new BadRequestException("Split between 2 to " + MAX_PARTS + " people");
                }
                if (owed.compareTo(BigDecimal.valueOf(people).movePointLeft(2)) < 0) {
                    throw new BadRequestException(OrderService.naira(owed) + " is too little to split between " + people);
                }
                amounts = BillSplit.equal(owed, people);
                for (int i = 1; i <= people; i++) {
                    labels.add("Part " + i);
                }
            }
            case "AMOUNT" -> {
                List<BigDecimal> asked = request.amounts() == null ? List.of() : request.amounts();
                if (asked.isEmpty() || asked.size() >= MAX_PARTS) {
                    throw new BadRequestException("Enter the amount each person is paying");
                }
                amounts = new ArrayList<>();
                BigDecimal sum = BigDecimal.ZERO;
                for (BigDecimal a : asked) {
                    if (a == null || a.signum() <= 0) {
                        throw new BadRequestException("Each amount must be more than ₦0");
                    }
                    BigDecimal kobo = a.setScale(2, RoundingMode.HALF_UP);
                    amounts.add(kobo);
                    sum = sum.add(kobo);
                    labels.add("Part " + amounts.size());
                }
                if (sum.compareTo(owed) > 0) {
                    throw new BadRequestException("The amounts add up to " + OrderService.naira(sum) + ", more than the " + OrderService.naira(owed) + " to pay");
                }
                if (sum.compareTo(owed) < 0) {
                    amounts.add(owed.subtract(sum));
                    labels.add("Rest of the bill");
                }
                if (amounts.size() < 2) {
                    throw new BadRequestException("That is the whole bill. Take the payment instead of splitting");
                }
            }
            case "ITEM" -> {
                if (Money.isPositive(o.getPaidAmount()) || Money.isPositive(o.getRoomAmount())) {
                    throw new BadRequestException("Money was already taken on " + o.getNumber() + ". Split what is left equally or by amount");
                }
                amounts = byItem(o, request, s, labels, items);
            }
            default -> throw new BadRequestException("Split equally (EQUAL), by item (ITEM) or by amount (AMOUNT)");
        }
        // The tax and service charge inside what is being divided, shared like the amounts.
        List<BillSplit.Share> shares = BillSplit.shares(amounts, BillSplit.inside(o.getTax(), owed, o.getTotal()),
                BillSplit.inside(o.getServiceCharge(), owed, o.getTotal()));
        OrderSplit split = splits.save(OrderSplit.builder().orderId(orderId).mode(mode).amount(owed).createdBy(user.getUserId())
                .createdByName(user.getFullName()).build());
        for (int i = 0; i < shares.size(); i++) {
            BillSplit.Share sh = shares.get(i);
            parts.save(OrderSplitPart.builder().splitId(split.getId()).orderId(orderId).position(i + 1).label(labels.get(i)).amount(sh.amount())
                    .tax(sh.tax()).serviceCharge(sh.serviceCharge()).items(items.isEmpty() ? null : write(items.get(i))).build());
        }
        o.setSplitParts(shares.size());
        orders.save(o);
    }

    /** A split by item: every live line on at least one part, a line on several parts shared between them by {@code shares}. */
    private List<BigDecimal> byItem(Order o, Bill.SplitRequest request, OutletSettings.Settings s, List<String> labels, List<List<Bill.PartItem>> items) {
        List<Bill.PartRequest> asked = request.parts() == null ? List.of() : request.parts();
        if (asked.size() < 2 || asked.size() > MAX_PARTS) {
            throw new BadRequestException("Put the items on 2 to " + MAX_PARTS + " bills");
        }
        Map<String, OrderLine> live = new LinkedHashMap<>();
        lines.findByOrderIdOrderByCreatedAt(o.getId()).stream().filter(OrderLine::isLive).forEach(l -> live.put(l.getId(), l));
        Map<String, Integer> totalShares = new LinkedHashMap<>();
        for (Bill.PartRequest p : asked) {
            if (p.lines() == null || p.lines().isEmpty()) {
                throw new BadRequestException("Every bill needs at least one item. Remove the empty one");
            }
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Bill.PartLine pl : p.lines()) {
                if (!live.containsKey(pl.lineId())) {
                    throw new BadRequestException("An item on the split is no longer on " + o.getNumber() + ". Reload the bill");
                }
                if (!seen.add(pl.lineId())) {
                    throw new BadRequestException(live.get(pl.lineId()).getName() + " is on the same bill twice");
                }
                int n = pl.shares() == null ? 1 : pl.shares();
                if (n < 1 || n > 1000) {
                    throw new BadRequestException("A share is 1 or more");
                }
                totalShares.merge(pl.lineId(), n, Integer::sum);
            }
        }
        for (OrderLine l : live.values()) {
            if (!totalShares.containsKey(l.getId())) {
                throw new BadRequestException(l.getName() + " isn't on anyone's bill yet");
            }
        }
        // What each line costs the guest (after discounts, with its tax when prices don't include it); the service charge rides along
        // in proportion because the bill's total is shared by these weights.
        Map<String, BigDecimal> worth = new LinkedHashMap<>();
        for (OrderLine l : live.values()) {
            OrderPricing.Input in = new OrderPricing.Input(l.getUnitPrice(), l.getQuantity(), l.getDiscount(), l.getTaxRate());
            BigDecimal priced = in.priced();
            worth.put(l.getId(), s.pricesIncludeTax() ? priced : priced.add(Money.percent(priced, l.getTaxRate() == null ? BigDecimal.ZERO : l.getTaxRate())));
        }
        BigDecimal all = worth.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (all.signum() <= 0) {
            throw new BadRequestException("There is nothing to pay for on " + o.getNumber());
        }
        List<BigDecimal> weights = new ArrayList<>();
        int i = 0;
        for (Bill.PartRequest p : asked) {
            i++;
            BigDecimal w = BigDecimal.ZERO;
            List<Bill.PartItem> on = new ArrayList<>();
            for (Bill.PartLine pl : p.lines()) {
                OrderLine l = live.get(pl.lineId());
                int n = pl.shares() == null ? 1 : pl.shares();
                int of = totalShares.get(pl.lineId());
                BigDecimal part = worth.get(pl.lineId()).multiply(BigDecimal.valueOf(n)).divide(BigDecimal.valueOf(of), 6, RoundingMode.HALF_UP);
                w = w.add(part);
                String share = n == of ? null : of == l.getQuantity() ? n + " of " + of : n + "/" + of;
                on.add(new Bill.PartItem(l.getId(), (l.getQuantity() > 1 ? l.getQuantity() + " × " : "") + l.getName(), share, part.setScale(2, RoundingMode.HALF_UP)));
            }
            weights.add(w);
            items.add(on);
            String label = p.label() == null || p.label().isBlank() ? "Guest " + i : p.label().trim();
            labels.add(label.length() > 80 ? label.substring(0, 80) : label);
        }
        if (weights.stream().anyMatch(w -> w.signum() <= 0)) {
            throw new BadRequestException("A bill with only free items has nothing to pay. Put something else on it or remove it");
        }
        return BillSplit.allocate(Money.of(o.balance()), weights);
    }

    @Transactional
    public void undo(String orderId) {
        HospioUserDetails user = CurrentUser.require();
        OutletSettings.Settings s = settings.get(orderService.peek(orderId).getHotelId());
        Order o = orderService.lock(orderId);
        requireTakesMoney(user, orderService.outlet(o.getOutletId()), o, s);
        OrderSplit split = splits.findFirstByOrderIdAndStatus(orderId, OrderSplit.ACTIVE)
                .orElseThrow(() -> new BadRequestException(o.getNumber() + " isn't split"));
        List<OrderSplitPart> ps = parts.findBySplitIdOrderByPosition(split.getId());
        OrderSplitPart paid = ps.stream().filter(p -> Money.isPositive(p.getPaidAmount()) || OrderSplitPart.PAID.equals(p.getStatus())).findFirst().orElse(null);
        if (paid != null) {
            throw new BadRequestException(paid.getLabel() + " is already paid, so the split stays. Collect the other parts");
        }
        split.setStatus(OrderSplit.UNDONE);
        split.setEndedAt(Instant.now());
        split.setEndedByName(user.getFullName());
        splits.save(split);
        o.setSplitParts(0);
        orders.save(o);
    }

    /**
     * One part of a split bill goes on a room (hotels): the same checks as charging the whole order, then only this part's amount is
     * posted to the folio (without tax and the service charge, as always: the folio adds its own). The order settles when its other
     * parts are paid too.
     */
    @Transactional
    public void chargePartToRoom(String orderId, String partId, String roomId) {
        HospioUserDetails user = CurrentUser.require();
        OrderRepository.Peek peek = orderService.peek(orderId);
        OutletSettings.Settings s = settings.get(peek.getHotelId());
        Outlet outlet = orderService.outlet(peek.getOutletId());
        if (!s.roomCharge()) {
            throw new BadRequestException("Charging to a room is switched off here (it needs rooms: a lounge, bar or restaurant has none)");
        }
        String room = roomId == null || roomId.isBlank() ? peek.getRoomId() : roomId;
        if (room == null) {
            throw new BadRequestException("Choose the room");
        }
        ReservationClient.ActiveStay stay = reservations.findActiveStay(room)
                .orElseThrow(() -> new BadRequestException("Nobody is checked in to that room"));
        if (!folios.hasOpenFolio(stay.bookingId())) {
            throw new BadRequestException("Room " + stay.roomNumber() + " has no open bill");
        }
        Order o = orderService.lock(orderId);
        if (!takesMoney(user, outlet, o, s) && !OutletAccess.frontOffice(user)
                && !(OutletAccess.waiter(user) && OutletAccess.own(user, o.getOpenedBy()) && staffOutlets.allowed(user, outlet))) {
            throw new ForbiddenException("This order belongs to the " + outlet.getName());
        }
        OrderService.requireLive(o);
        if (o.isSettled()) {
            throw new BadRequestException(o.getNumber() + " is already settled");
        }
        OrderSplit split = splits.findFirstByOrderIdAndStatus(orderId, OrderSplit.ACTIVE)
                .orElseThrow(() -> new BadRequestException(o.getNumber() + " isn't split. Charge the whole order to the room"));
        OrderSplitPart part = parts.findBySplitIdOrderByPosition(split.getId()).stream().filter(p -> p.getId().equals(partId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("That part isn't on this bill"));
        BigDecimal due = Money.of(part.due().min(o.balance()));
        if (OrderSplitPart.PAID.equals(part.getStatus()) || !Money.isPositive(due)) {
            throw new BadRequestException(part.getLabel() + " is already paid");
        }
        // Tax and service charge in proportion when the part was already paid in part.
        BigDecimal tax = BillSplit.inside(part.getTax(), due, part.getAmount());
        BigDecimal sc = BillSplit.inside(part.getServiceCharge(), due, part.getAmount());
        part.setPaidAmount(Money.of(part.getPaidAmount().add(due)));
        part.setStatus(OrderSplitPart.PAID);
        part.setMethod("ROOM");
        part.setRoomNumber(stay.roomNumber());
        part.setPaidAt(Instant.now());
        part.setPaidByName(user.getFullName());
        parts.save(part);
        o.setRoomAmount(Money.of(o.getRoomAmount().add(due)));
        if (o.getRoomId() == null) {
            o.setRoomId(room);
            o.setRoomNumber(stay.roomNumber());
            o.setBookingId(stay.bookingId());
        }
        orderService.settleWhenCovered(o, "ROOM");
        orders.save(o);
        events.publish(KafkaTopics.CHARGE_POSTED, ChargePostedEvent.builder().bookingId(stay.bookingId()).roomId(room)
                .roomNumber(stay.roomNumber()).sourceModule(outlet.getKind().name()).sourceRef("ORDER-PART-" + part.getId())
                .description(outlet.getName() + " · " + o.getNumber() + " (" + part.getLabel().toLowerCase(Locale.ROOT) + " of a split bill)")
                .amount(Money.of(due.subtract(tax).subtract(sc))).taxAmount(Money.of(tax)).category(outlet.getKind() == Outlet.Kind.BAR ? "BAR" : "FOOD").build());
    }

    String write(List<Bill.PartItem> items) {
        try {
            return mapper.writeValueAsString(items);
        } catch (Exception e) {
            return null;
        }
    }

    List<Bill.PartItem> read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, ITEMS);
        } catch (Exception e) {
            return null;
        }
    }
}
