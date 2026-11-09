package com.hospio.housekeeping.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ChargePostedEvent;
import com.hospio.common.kafka.events.NoticeEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.housekeeping.client.LaundryClients;
import com.hospio.housekeeping.client.ReservationClient;
import com.hospio.housekeeping.dto.LaundryDtos;
import com.hospio.housekeeping.dto.TaskRequests;
import com.hospio.housekeeping.entity.LaundryOrder;
import com.hospio.housekeeping.entity.LaundryPrice;
import com.hospio.housekeeping.entity.LinenBatch;
import com.hospio.housekeeping.repository.LaundryOrderRepository;
import com.hospio.housekeeping.repository.LaundryPriceRepository;
import com.hospio.housekeeping.repository.LinenBatchRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Guest laundry, the price list and hotel linen batches (docs/laundry-maintenance.md). Rules live in {@link LaundryRules}. */
@Service
@RequiredArgsConstructor
public class LaundryService {

    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    static final Set<String> CATEGORIES = Set.of("WASH_IRON", "DRY_CLEAN", "IRON_ONLY", "OTHER");
    static final String LINEN = "Linen";

    private final LaundryOrderRepository orders;
    private final LaundryPriceRepository prices;
    private final LinenBatchRepository batches;
    private final ReservationClient reservations;
    private final LaundryClients clients;
    private final EventPublisher events;
    private final ObjectMapper mapper;

    // ---- price list ----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<LaundryDtos.PriceView> prices() {
        return prices.findByHotelIdOrderBySortOrderAscNameAsc(TenantContext.requireHotel()).stream().map(LaundryService::priceView).toList();
    }

    @Transactional
    public LaundryDtos.PriceView savePrice(String id, LaundryDtos.PriceRequest r) {
        String hotel = TenantContext.requireHotel();
        String name = r == null ? null : blank(r.name());
        if (name == null) {
            throw new BadRequestException("Name the item, e.g. Shirt");
        }
        if (name.length() > 100) {
            throw new BadRequestException("Keep the name under 100 characters");
        }
        if (r.price() == null || r.price().signum() <= 0) {
            throw new BadRequestException("Enter a price above zero");
        }
        BigDecimal price = Money.of(r.price());
        BigDecimal express = r.expressPrice() == null ? LaundryRules.defaultExpress(price) : Money.of(r.expressPrice());
        if (express.compareTo(price) < 0) {
            throw new BadRequestException("The express price can't be below the normal price");
        }
        String category = r.category() == null || r.category().isBlank() ? "WASH_IRON" : r.category().trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        if (!CATEGORIES.contains(category)) {
            throw new BadRequestException("Pick a category: wash and iron, dry clean, iron only or other");
        }
        LaundryPrice p = id == null ? new LaundryPrice() : prices.findById(id).orElseThrow(() -> new ResourceNotFoundException("Price not found"));
        if (prices.findByHotelIdOrderBySortOrderAscNameAsc(hotel).stream().anyMatch(x -> !x.getId().equals(p.getId()) && x.getName().equalsIgnoreCase(name))) {
            throw new BadRequestException("There's already a price for " + name);
        }
        p.setName(name);
        p.setCategory(category);
        p.setPrice(price);
        p.setExpressPrice(express);
        if (r.active() != null) {
            p.setActive(r.active());
        }
        if (r.sortOrder() != null) {
            p.setSortOrder(r.sortOrder());
        }
        return priceView(prices.save(p));
    }

    // ---- guest orders --------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<LaundryDtos.OrderView> orders(String status) {
        String hotel = TenantContext.requireHotel();
        List<LaundryOrder> rows = "all".equalsIgnoreCase(status) ? orders.findTop300ByHotelIdOrderByCreatedAtDesc(hotel)
                : orders.findByHotelIdAndStatusInOrderByCreatedAt(hotel, EnumSet.of(LaundryOrder.Status.RECEIVED, LaundryOrder.Status.IN_PROGRESS,
                LaundryOrder.Status.READY));
        return rows.stream().map(this::orderView).toList();
    }

    @Transactional(readOnly = true)
    public LaundryDtos.OrderView order(String id) {
        return orderView(find(id));
    }

    @Transactional
    public LaundryDtos.OrderView create(LaundryDtos.OrderRequest r) {
        HospioUserDetails u = CurrentUser.require();
        String hotel = TenantContext.requireHotel();
        String roomId = r == null ? null : blank(r.roomId());
        if (roomId == null) {
            throw new BadRequestException("Pick the guest's room");
        }
        if (r.lines() == null || r.lines().isEmpty()) {
            throw new BadRequestException("Add at least one item");
        }
        boolean express = Boolean.TRUE.equals(r.express());
        Map<String, LaundryPrice> pm = prices.findByHotelIdOrderBySortOrderAscNameAsc(hotel).stream()
                .collect(Collectors.toMap(LaundryPrice::getId, Function.identity()));
        Map<String, BigDecimal> qty = new LinkedHashMap<>();
        for (LaundryDtos.OrderLine l : r.lines()) {
            if (l == null || l.priceId() == null || l.quantity() == null) {
                throw new BadRequestException("Pick an item and a quantity on every line");
            }
            if (l.quantity().signum() <= 0 || l.quantity().stripTrailingZeros().scale() > 0) {
                throw new BadRequestException("Quantities are whole numbers above zero");
            }
            LaundryPrice p = pm.get(l.priceId());
            if (p == null) {
                throw new BadRequestException("That item isn't on the price list");
            }
            if (!p.isActive()) {
                throw new BadRequestException(p.getName() + " is no longer on the price list");
            }
            qty.merge(p.getId(), l.quantity(), BigDecimal::add);
        }
        List<LaundryRules.Line> lines = qty.entrySet().stream().map(e -> {
            LaundryPrice p = pm.get(e.getKey());
            return LaundryRules.Line.of(p.getId(), p.getName(), e.getValue(), LaundryRules.unitPrice(p.getPrice(), p.getExpressPrice(), express));
        }).toList();
        ReservationClient.ActiveStay stay = reservations.activeStay(roomId).orElseThrow(() -> new BadRequestException("Nobody is checked in to that room"));
        Instant now = Instant.now();
        if (r.promisedAt() != null && r.promisedAt().isBefore(now)) {
            throw new BadRequestException("The promised time can't be in the past");
        }
        String note = blank(r.note());
        if (note != null && note.length() > 300) {
            throw new BadRequestException("Keep the note under 300 characters");
        }
        orders.lock("laundry-number:" + hotel);
        LaundryOrder o = LaundryOrder.builder().number(String.format("LDR-%05d", orders.lastNumber(hotel) + 1)).roomId(roomId).roomNumber(stay.roomNumber())
                .bookingId(stay.bookingId()).guestName(stay.guestName()).lines(write(lines))
                .items(lines.stream().map(l -> l.quantity().stripTrailingZeros().toPlainString() + " × " + l.name()).collect(Collectors.joining(", ")))
                .total(LaundryRules.total(lines)).express(express).promisedAt(r.promisedAt() != null ? r.promisedAt() : now.plus(LaundryRules.turnaround(express)))
                .note(note).receivedBy(u.getUserId()).receivedByName(u.getFullName()).build();
        orders.save(o);
        if (Boolean.TRUE.equals(r.chargeToRoom())) {
            charge(o);
        }
        return orderView(o);
    }

    @Transactional
    public LaundryDtos.OrderView move(String id, LaundryDtos.StatusRequest r) {
        LaundryOrder o = find(id);
        LaundryOrder.Status to;
        try {
            to = r == null || r.status() == null ? null : LaundryOrder.Status.valueOf(r.status().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            to = null;
        }
        LaundryRules.checkMove(o, to);
        o.setStatus(to);
        if (to == LaundryOrder.Status.DELIVERED) {
            o.setDeliveredAt(Instant.now());
        }
        orders.save(o);
        return orderView(o);
    }

    @Transactional
    public LaundryDtos.OrderView chargeToRoom(String id) {
        LaundryOrder o = find(id);
        charge(o);
        return orderView(o);
    }

    /** The stay must still be checked in with an open bill; posts CHARGE_POSTED for the order total (folio dedupes by reference). */
    private void charge(LaundryOrder o) {
        LaundryRules.checkSettle(o);
        if (o.getRoomId() == null) {
            throw new BadRequestException(o.getNumber() + " isn't for a room");
        }
        ReservationClient.ActiveStay stay = reservations.activeStay(o.getRoomId())
                .orElseThrow(() -> new BadRequestException("Nobody is checked in to room " + o.getRoomNumber() + " now · take payment instead"));
        if (o.getBookingId() != null && !o.getBookingId().equals(stay.bookingId())) {
            throw new BadRequestException(o.getGuestName() + " has checked out of room " + o.getRoomNumber() + " · take payment instead");
        }
        if (!clients.hasOpenFolio(stay.bookingId())) {
            throw new BadRequestException("Room " + o.getRoomNumber() + " has no open bill · take payment instead");
        }
        o.setBookingId(stay.bookingId());
        o.setChargedToRoom(true);
        o.setChargedAt(Instant.now());
        orders.save(o);
        events.publish(KafkaTopics.CHARGE_POSTED, ChargePostedEvent.builder().bookingId(stay.bookingId()).roomId(o.getRoomId()).roomNumber(o.getRoomNumber())
                .sourceModule("LAUNDRY").sourceRef("LAUNDRY-" + o.getId()).description("Laundry · " + o.getNumber() + (o.isExpress() ? " (express)" : ""))
                .amount(o.getTotal()).taxAmount(BigDecimal.ZERO).category("LAUNDRY").build());
    }

    @Transactional
    public LaundryDtos.OrderView paid(String id, LaundryDtos.PaidRequest r) {
        LaundryOrder o = find(id);
        String paymentId = r == null ? null : blank(r.paymentId());
        if (paymentId == null) {
            throw new BadRequestException("Take the payment first");
        }
        LaundryRules.checkSettle(o);
        if (orders.existsByPaymentId(paymentId)) {
            throw new BadRequestException("That payment is already used for another laundry order");
        }
        LaundryClients.Payment p = clients.payment(paymentId);
        if (!"APPROVED".equals(p.status())) {
            throw new BadRequestException("That payment isn't approved (" + (p.status() == null ? "unknown" : p.status().toLowerCase(Locale.ROOT).replace('_', ' ')) + ")");
        }
        if (p.amount() == null || p.amount().compareTo(o.getTotal()) < 0) {
            throw new BadRequestException("That payment (" + Money.of(p.amount()) + ") is less than " + o.getNumber() + " (" + o.getTotal() + ")");
        }
        o.setPaid(true);
        o.setPaymentId(paymentId);
        o.setPaidAt(Instant.now());
        o.setPaidAmount(Money.of(p.amount()));
        o.setPaymentMethod(p.method());
        orders.save(o);
        return orderView(o);
    }

    @Transactional
    public LaundryDtos.OrderView cancel(String id, LaundryDtos.ReasonRequest r) {
        LaundryOrder o = find(id);
        String reason = reason(r, "Say why it is cancelled");
        LaundryRules.checkCancel(o);
        o.setStatus(LaundryOrder.Status.CANCELLED);
        o.setCancelReason(reason);
        o.setCancelledAt(Instant.now());
        orders.save(o);
        return orderView(o);
    }

    @Transactional
    public LaundryDtos.OrderView pocketNote(String id, TaskRequests.PocketNoteRequest request) {
        LaundryOrder o = find(id);
        if (request.note().length() > 300) {
            throw new BadRequestException("Keep the note under 300 characters");
        }
        o.setPocketNote(request.note().trim());
        orders.save(o);
        return orderView(o);
    }

    // ---- linen batches -------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<LaundryDtos.BatchView> batches(String status) {
        return batches.findTop200ByHotelIdOrderByCreatedAtDesc(TenantContext.requireHotel()).stream()
                .filter(b -> !"open".equalsIgnoreCase(status) || b.getStatus() == LinenBatch.Status.SENT || b.getStatus() == LinenBatch.Status.RECEIVED
                        || b.getStatus() == LinenBatch.Status.PART_RETURNED)
                .map(this::batchView).toList();
    }

    @Transactional
    public LaundryDtos.BatchView send(LaundryDtos.BatchRequest r) {
        HospioUserDetails u = CurrentUser.require();
        String hotel = TenantContext.requireHotel();
        Map<String, BigDecimal> wanted = itemLines(r == null ? null : r.lines(), "send");
        Map<String, String[]> items = clients.linenItems();
        List<LaundryRules.BatchLine> lines = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> w : wanted.entrySet()) {
            String[] i = items.get(w.getKey());
            if (i == null) {
                throw new BadRequestException("That item isn't in this hotel's stock");
            }
            if (!LINEN.equalsIgnoreCase(i[2])) {
                throw new BadRequestException(i[0] + " isn't linen");
            }
            lines.add(new LaundryRules.BatchLine(w.getKey(), i[0], i[1], w.getValue(), null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null));
        }
        String note = blank(r.note());
        if (note != null && note.length() > 300) {
            throw new BadRequestException("Keep the note under 300 characters");
        }
        orders.lock("linen-number:" + hotel);
        LinenBatch b = LinenBatch.builder().number(String.format("LNB-%05d", batches.lastNumber(hotel) + 1)).lines(write(lines)).note(note)
                .sentBy(u.getUserId()).sentByName(u.getFullName()).build();
        batches.save(b);
        BigDecimal pieces = lines.stream().map(LaundryRules.BatchLine::sent).reduce(BigDecimal.ZERO, BigDecimal::add);
        events.publish(KafkaTopics.NOTICE, NoticeEvent.builder().kind("LINEN").title("Soiled linen on its way · " + b.getNumber())
                .body(pieces.stripTrailingZeros().toPlainString() + " pieces from " + u.getFullName()).link("/laundry/linen").roles(List.of("LAUNDRY")).build());
        return batchView(b);
    }

    /** Laundry counts what arrived: that moves Housekeeping → Laundry store; anything short is written off as missing. */
    @Transactional
    public LaundryDtos.BatchView receive(String id, LaundryDtos.BatchRequest r) {
        HospioUserDetails u = CurrentUser.require();
        LinenBatch b = batch(id);
        if (b.getStatus() != LinenBatch.Status.SENT) {
            throw new BadRequestException(b.getNumber() + " is already received");
        }
        List<LaundryRules.BatchLine> lines = lines(b);
        Map<String, BigDecimal> counted = new LinkedHashMap<>();
        if (r != null && r.lines() != null) {
            for (LaundryDtos.ItemLine l : r.lines()) {
                if (l == null || l.itemId() == null || l.quantity() == null) {
                    throw new BadRequestException("Count every line");
                }
                if (l.quantity().signum() < 0 || l.quantity().stripTrailingZeros().scale() > 0) {
                    throw new BadRequestException("Counts are whole numbers, zero or more");
                }
                counted.merge(l.itemId(), l.quantity(), BigDecimal::add);
            }
        }
        Map<String, BigDecimal> in = new LinkedHashMap<>();
        Map<String, BigDecimal> missing = new LinkedHashMap<>();
        List<LaundryRules.BatchLine> next = new ArrayList<>();
        for (LaundryRules.BatchLine l : lines) {
            BigDecimal got = counted.containsKey(l.itemId()) ? counted.remove(l.itemId()) : l.sent();
            if (got.compareTo(l.sent()) > 0) {
                throw new BadRequestException("More " + l.name() + " counted (" + plain(got) + ") than were sent (" + plain(l.sent()) + ")");
            }
            if (got.signum() > 0) {
                in.put(l.itemId(), got);
            }
            BigDecimal miss = l.sent().subtract(got);
            if (miss.signum() > 0) {
                missing.put(l.itemId(), miss);
            }
            next.add(l.with(got, l.returned(), l.condemned(), miss));
        }
        if (!counted.isEmpty()) {
            throw new BadRequestException("Something counted isn't on " + b.getNumber());
        }
        Map<String, BigDecimal> costs = new LinkedHashMap<>();
        if (!in.isEmpty()) {
            clients.move(b.getNumber() + "-IN", "TRANSFER", "HOUSEKEEPING", "LAUNDRY", "LAUNDRY", "Soiled linen " + b.getNumber(), LINEN, in)
                    .forEach(m -> costs.put(m.itemId(), m.unitCost()));
        }
        BigDecimal lostValue = BigDecimal.ZERO;
        if (!missing.isEmpty()) {
            for (LaundryClients.Moved m : clients.move(b.getNumber() + "-MISSING", "WASTE", "HOUSEKEEPING", null, "HOUSEKEEPING",
                    "Missing between housekeeping and laundry on " + b.getNumber(), LINEN, missing)) {
                lostValue = lostValue.add(m.value());
                costs.putIfAbsent(m.itemId(), m.unitCost());
            }
        }
        List<LaundryRules.BatchLine> costed = next.stream().map(l -> new LaundryRules.BatchLine(l.itemId(), l.name(), l.unit(), l.sent(), l.received(),
                l.returned(), l.condemned(), l.lost(), costs.getOrDefault(l.itemId(), l.unitCost()))).toList();
        b.setLines(write(costed));
        b.setStatus(LinenBatch.Status.RECEIVED);
        b.setReceivedAt(Instant.now());
        b.setReceivedByName(u.getFullName());
        b.setLostValue(Money.of(b.getLostValue().add(lostValue)));
        batches.save(b);
        if (!missing.isEmpty() && b.getSentBy() != null) {
            events.publish(KafkaTopics.NOTICE, NoticeEvent.builder().kind("LINEN").title(b.getNumber() + " arrived short")
                    .body(missing.entrySet().stream().map(e -> plain(e.getValue()) + " × " + nameOf(costed, e.getKey())).collect(Collectors.joining(", ")) + " missing")
                    .link("/linen").userIds(List.of(b.getSentBy())).roles(List.of("MANAGER")).build());
        }
        return batchView(b);
    }

    /** Clean linen goes back (Laundry → Housekeeping store); condemned linen is written off the Laundry store. */
    @Transactional
    public LaundryDtos.BatchView giveBack(String id, LaundryDtos.ReturnRequest r) {
        LinenBatch b = batch(id);
        if (b.getStatus() == LinenBatch.Status.SENT) {
            throw new BadRequestException("Receive " + b.getNumber() + " before returning it");
        }
        if (b.getStatus() == LinenBatch.Status.RETURNED || b.getStatus() == LinenBatch.Status.CLOSED) {
            throw new BadRequestException(b.getNumber() + " is already " + b.getStatus().name().toLowerCase(Locale.ROOT));
        }
        if (r == null || r.lines() == null || r.lines().isEmpty()) {
            throw new BadRequestException("Enter what is going back");
        }
        List<LaundryRules.BatchLine> lines = lines(b);
        Map<String, LaundryRules.BatchLine> byItem = lines.stream().collect(Collectors.toMap(LaundryRules.BatchLine::itemId, Function.identity(), (a, c) -> a,
                LinkedHashMap::new));
        Map<String, BigDecimal> back = new LinkedHashMap<>();
        Map<String, BigDecimal> condemned = new LinkedHashMap<>();
        for (LaundryDtos.ReturnLine l : r.lines()) {
            if (l == null || l.itemId() == null) {
                continue;
            }
            LaundryRules.BatchLine line = byItem.get(l.itemId());
            if (line == null) {
                throw new BadRequestException("That linen isn't on " + b.getNumber());
            }
            BigDecimal ret = l.returned() == null ? BigDecimal.ZERO : l.returned();
            BigDecimal con = l.condemned() == null ? BigDecimal.ZERO : l.condemned();
            if (ret.signum() < 0 || con.signum() < 0 || ret.stripTrailingZeros().scale() > 0 || con.stripTrailingZeros().scale() > 0) {
                throw new BadRequestException("Counts are whole numbers, zero or more");
            }
            BigDecimal already = back.getOrDefault(l.itemId(), BigDecimal.ZERO).add(condemned.getOrDefault(l.itemId(), BigDecimal.ZERO));
            if (ret.add(con).add(already).compareTo(line.outstanding()) > 0) {
                throw new BadRequestException("Only " + plain(line.outstanding()) + " " + line.name() + " still at the laundry on " + b.getNumber());
            }
            if (ret.signum() > 0) {
                back.merge(l.itemId(), ret, BigDecimal::add);
            }
            if (con.signum() > 0) {
                condemned.merge(l.itemId(), con, BigDecimal::add);
            }
        }
        if (back.isEmpty() && condemned.isEmpty()) {
            throw new BadRequestException("Enter what is going back");
        }
        String reason = r.reason() == null ? null : blank(r.reason());
        if (!condemned.isEmpty() && reason == null) {
            throw new BadRequestException("Say why the linen is condemned");
        }
        if (reason != null && reason.length() > 300) {
            throw new BadRequestException("Keep the reason under 300 characters");
        }
        int n = b.getReturns() + 1;
        if (!back.isEmpty()) {
            clients.move(b.getNumber() + "-R" + n, "TRANSFER", "LAUNDRY", "HOUSEKEEPING", "LAUNDRY", "Clean linen back on " + b.getNumber(), LINEN, back);
        }
        BigDecimal value = BigDecimal.ZERO;
        if (!condemned.isEmpty()) {
            for (LaundryClients.Moved m : clients.move(b.getNumber() + "-C" + n, "WASTE", "LAUNDRY", null, "LAUNDRY",
                    "Condemned on " + b.getNumber() + ": " + reason, LINEN, condemned)) {
                value = value.add(m.value());
            }
        }
        List<LaundryRules.BatchLine> next = lines.stream().map(l -> l.with(l.received(), l.returned().add(back.getOrDefault(l.itemId(), BigDecimal.ZERO)),
                l.condemned().add(condemned.getOrDefault(l.itemId(), BigDecimal.ZERO)), l.lost())).toList();
        b.setLines(write(next));
        b.setReturns(n);
        b.setCondemnedValue(Money.of(b.getCondemnedValue().add(value)));
        b.setStatus(LaundryRules.afterReturn(next));
        b.setReturnedAt(Instant.now());
        batches.save(b);
        if (!back.isEmpty()) {
            events.publish(KafkaTopics.NOTICE, NoticeEvent.builder().kind("LINEN").title("Clean linen back · " + b.getNumber())
                    .body(back.entrySet().stream().map(e -> plain(e.getValue()) + " × " + nameOf(next, e.getKey())).collect(Collectors.joining(", ")))
                    .link("/linen").roles(List.of("HOUSEKEEPING")).build());
        }
        return batchView(b);
    }

    /** What never comes back is written off the Laundry store as lost and the batch closes. LAUNDRY Full or a manager. */
    @Transactional
    public LaundryDtos.BatchView close(String id, LaundryDtos.ReasonRequest r) {
        HospioUserDetails u = CurrentUser.require();
        if (u.isReadOnly() || !(u.isPlatform() || u.isManagerOrOwner() || u.has(Role.LAUNDRY, AccessLevel.FULL))) {
            throw new ForbiddenException("Closing a batch short is for the laundry supervisor or a manager");
        }
        LinenBatch b = batch(id);
        String reason = reason(r, "Say why the rest won't come back");
        if (b.getStatus() == LinenBatch.Status.SENT) {
            throw new BadRequestException("Receive " + b.getNumber() + " before closing it");
        }
        if (b.getStatus() == LinenBatch.Status.RETURNED || b.getStatus() == LinenBatch.Status.CLOSED) {
            throw new BadRequestException(b.getNumber() + " is already " + b.getStatus().name().toLowerCase(Locale.ROOT));
        }
        List<LaundryRules.BatchLine> lines = lines(b);
        Map<String, BigDecimal> lost = new LinkedHashMap<>();
        lines.forEach(l -> {
            if (l.outstanding().signum() > 0) {
                lost.put(l.itemId(), l.outstanding());
            }
        });
        BigDecimal value = BigDecimal.ZERO;
        if (!lost.isEmpty()) {
            for (LaundryClients.Moved m : clients.move(b.getNumber() + "-LOST", "WASTE", "LAUNDRY", null, "LAUNDRY", "Lost on " + b.getNumber() + ": " + reason,
                    LINEN, lost)) {
                value = value.add(m.value());
            }
        }
        b.setLines(write(lines.stream().map(l -> l.with(l.received(), l.returned(), l.condemned(), l.lost().add(lost.getOrDefault(l.itemId(), BigDecimal.ZERO))))
                .toList()));
        b.setLostValue(Money.of(b.getLostValue().add(value)));
        b.setStatus(LinenBatch.Status.CLOSED);
        b.setClosedReason(reason);
        batches.save(b);
        return batchView(b);
    }

    // ---- money ---------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public LaundryDtos.Summary summary(LocalDate from, LocalDate to) {
        String hotel = TenantContext.requireHotel();
        LocalDate t = to == null ? LocalDate.now(LAGOS) : to;
        LocalDate f = from == null ? t.minusDays(29) : from;
        if (f.isAfter(t)) {
            throw new BadRequestException("The start date is after the end date");
        }
        Instant a = f.atStartOfDay(LAGOS).toInstant();
        Instant z = t.plusDays(1).atStartOfDay(LAGOS).toInstant();
        BigDecimal charged = orders.findByHotelIdAndChargedAtBetween(hotel, a, z).stream().filter(LaundryOrder::isChargedToRoom).map(LaundryOrder::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paid = orders.findByHotelIdAndPaidAtBetween(hotel, a, z).stream().filter(LaundryOrder::isPaid).map(LaundryOrder::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<LaundryOrder> made = orders.findByHotelIdAndCreatedAtBetween(hotel, a, z);
        long open = made.stream().filter(o -> o.getStatus() != LaundryOrder.Status.CANCELLED && o.getStatus() != LaundryOrder.Status.DELIVERED).count();
        long cancelled = made.stream().filter(o -> o.getStatus() == LaundryOrder.Status.CANCELLED).count();
        List<LinenBatch> touched = batches.findByHotelIdAndUpdatedAtBetween(hotel, a, z);
        BigDecimal condemned = touched.stream().map(LinenBatch::getCondemnedValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lost = touched.stream().map(LinenBatch::getLostValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new LaundryDtos.Summary(f, t, made.stream().filter(o -> o.getStatus() != LaundryOrder.Status.CANCELLED).count(), Money.of(charged.add(paid)),
                Money.of(charged), Money.of(paid), open, cancelled, Money.of(condemned), Money.of(lost));
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private LaundryOrder find(String id) {
        return orders.findById(id).orElseThrow(() -> new ResourceNotFoundException("Laundry order not found"));
    }

    private LinenBatch batch(String id) {
        return batches.findById(id).orElseThrow(() -> new ResourceNotFoundException("Linen batch not found"));
    }

    private static Map<String, BigDecimal> itemLines(List<LaundryDtos.ItemLine> lines, String verb) {
        if (lines == null || lines.isEmpty()) {
            throw new BadRequestException("Add at least one item to " + verb);
        }
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (LaundryDtos.ItemLine l : lines) {
            if (l == null || l.itemId() == null || l.quantity() == null) {
                throw new BadRequestException("Pick an item and a quantity on every line");
            }
            if (l.quantity().signum() < 0 || l.quantity().stripTrailingZeros().scale() > 0) {
                throw new BadRequestException("Quantities are whole numbers above zero");
            }
            if (l.quantity().signum() > 0) {
                out.merge(l.itemId(), l.quantity(), BigDecimal::add);
            }
        }
        if (out.isEmpty()) {
            throw new BadRequestException("Enter a quantity above zero");
        }
        return out;
    }

    private static String reason(LaundryDtos.ReasonRequest r, String missing) {
        String s = r == null ? null : blank(r.reason());
        if (s == null) {
            throw new BadRequestException(missing);
        }
        if (s.length() > 300) {
            throw new BadRequestException("Keep the reason under 300 characters");
        }
        return s;
    }

    private List<LaundryRules.BatchLine> lines(LinenBatch b) {
        try {
            return mapper.readValue(b.getLines(), new TypeReference<List<LaundryRules.BatchLine>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("Linen batch " + b.getNumber() + " can't be read", e);
        }
    }

    private List<LaundryDtos.OrderLineView> orderLines(LaundryOrder o) {
        if (o.getLines() == null || o.getLines().isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(o.getLines(), new TypeReference<List<LaundryDtos.OrderLineView>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String write(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private LaundryDtos.OrderView orderView(LaundryOrder o) {
        return new LaundryDtos.OrderView(o.getId(), o.getNumber(), o.getBookingId(), o.getGuestName(), o.getRoomId(), o.getRoomNumber(), orderLines(o),
                o.isExpress(), o.getTotal(), o.getStatus().name(), o.isChargedToRoom() ? "ROOM" : o.isPaid() ? "PAID" : null, o.isChargedToRoom(), o.isPaid(),
                o.getChargedAt(), o.getPaidAt(), o.getPaidAmount(), o.getPaymentId(), o.getPaymentMethod(), o.getPromisedAt(), o.getNote(), o.getPocketNote(),
                o.getCancelReason(), o.getReceivedByName(), o.getCreatedAt(), o.getDeliveredAt());
    }

    private LaundryDtos.BatchView batchView(LinenBatch b) {
        List<LaundryRules.BatchLine> lines = lines(b);
        List<LaundryDtos.BatchLineView> views = lines.stream().map(l -> new LaundryDtos.BatchLineView(l.itemId(), l.name(), l.unit(), l.sent(), l.received(),
                l.returned(), l.condemned(), l.lost(), l.missing(), l.outstanding(), l.unitCost())).toList();
        return new LaundryDtos.BatchView(b.getId(), b.getNumber(), b.getStatus().name(), views, b.getNote(), b.getSentByName(), b.getCreatedAt(),
                b.getReceivedAt(), b.getReceivedByName(), b.getReturnedAt(), b.getClosedReason(), b.getCondemnedValue(), b.getLostValue(),
                lines.stream().map(LaundryRules.BatchLine::sent).reduce(BigDecimal.ZERO, BigDecimal::add), LaundryRules.missing(lines),
                lines.stream().map(LaundryRules.BatchLine::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static LaundryDtos.PriceView priceView(LaundryPrice p) {
        return new LaundryDtos.PriceView(p.getId(), p.getName(), p.getCategory(), p.getPrice(), p.getExpressPrice(), p.isActive(), p.getSortOrder());
    }

    private static String nameOf(List<LaundryRules.BatchLine> lines, String itemId) {
        return lines.stream().filter(l -> l.itemId().equals(itemId)).map(LaundryRules.BatchLine::name).findFirst().orElse("linen");
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
